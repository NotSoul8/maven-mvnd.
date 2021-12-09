/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.cli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import com.google.inject.AbstractModule;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.ParseException;
import org.apache.maven.Maven;
import org.apache.maven.cli.configuration.ConfigurationProcessor;
import org.apache.maven.cli.event.ExecutionEventLogger;
import org.apache.maven.cli.internal.extension.model.CoreExtension;
import org.apache.maven.cli.transfer.Slf4jMavenTransferListener;
import org.apache.maven.eventspy.internal.EventSpyDispatcher;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.extension.internal.CoreExports;
import org.apache.maven.plugin.ExtensionRealmCache;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.PluginArtifactsCache;
import org.apache.maven.plugin.PluginRealmCache;
import org.apache.maven.plugin.version.PluginVersionResolver;
import org.apache.maven.project.artifact.ProjectArtifactsCache;
import org.apache.maven.toolchain.building.ToolchainsBuilder;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.transfer.TransferListener;
import org.mvndaemon.mvnd.cache.invalidating.InvalidatingExtensionRealmCache;
import org.mvndaemon.mvnd.cache.invalidating.InvalidatingPluginArtifactsCache;
import org.mvndaemon.mvnd.cache.invalidating.InvalidatingPluginRealmCache;
import org.mvndaemon.mvnd.cache.invalidating.InvalidatingProjectArtifactsCache;
import org.mvndaemon.mvnd.common.Environment;
import org.mvndaemon.mvnd.common.Os;
import org.mvndaemon.mvnd.logging.internal.Slf4jLoggerManager;
import org.mvndaemon.mvnd.logging.smart.BuildEventListener;
import org.mvndaemon.mvnd.logging.smart.LoggingExecutionListener;
import org.mvndaemon.mvnd.logging.smart.LoggingOutputStream;
import org.mvndaemon.mvnd.plugin.CachingPluginVersionResolver;
import org.mvndaemon.mvnd.plugin.CliMavenPluginManager;
import org.mvndaemon.mvnd.transfer.DaemonMavenTransferListener;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;

import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;

/**
 * Derived MavenCli for mvnd
 *
 * @author Guillaume Nodet
 */
public class DaemonMavenCli extends MavenCli {

    public static final String RESUME = "r";

    public static final String RAW_STREAMS = "raw-streams";

    private final PlexusContainer container;

    /** Non-volatile, assuming that it is accessed only from the main thread */
    private BuildEventListener buildEventListener = BuildEventListener.dummy();

    private Map<String, String> clientEnv = Collections.emptyMap();

    public DaemonMavenCli() throws Exception {
        slf4jLoggerFactory = LoggerFactory.getILoggerFactory();
        slf4jLogger = slf4jLoggerFactory.getLogger(this.getClass().getName());
        plexusLoggerManager = new Slf4jLoggerManager();

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        classWorld = new ClassWorld("plexus.core", cl);

        container = super.container(new CliRequest(null, classWorld));

        eventSpyDispatcher = container.lookup(EventSpyDispatcher.class);
        maven = container.lookup(Maven.class);
        executionRequestPopulator = container.lookup(MavenExecutionRequestPopulator.class);
        modelProcessor = createModelProcessor(container);
        configurationProcessors = container.lookupMap(ConfigurationProcessor.class);
        toolchainsBuilder = container.lookup(ToolchainsBuilder.class);
        dispatcher = (DefaultSecDispatcher) container.lookup(SecDispatcher.class, "maven");
    }

    public int main(List<String> arguments,
            String workingDirectory,
            String projectDirectory,
            Map<String, String> clientEnv,
            BuildEventListener buildEventListener) throws Exception {
        this.clientEnv = clientEnv;
        this.buildEventListener = buildEventListener;
        try {
            CliRequest req = new CliRequest(null, null);
            req.args = arguments.toArray(new String[0]);
            req.workingDirectory = new File(workingDirectory).getCanonicalPath();
            req.multiModuleProjectDirectory = new File(projectDirectory);
            Properties props = (Properties) System.getProperties().clone();
            try {
                return doMain(req);
            } finally {
                System.setProperties(props);
                eventSpyDispatcher.close();
            }
        } finally {
            this.clientEnv = Collections.emptyMap();
            this.buildEventListener = BuildEventListener.dummy();
        }
    }

    protected void initialize(CliRequest cliRequest)
            throws ExitException {
        cliRequest.classWorld = classWorld;

        if (cliRequest.workingDirectory == null) {
            cliRequest.workingDirectory = System.getProperty("user.dir");
        }

        if (cliRequest.multiModuleProjectDirectory == null) {
            printErr(String.format("-D%s system property is not set.", MULTIMODULE_PROJECT_DIRECTORY));
            throw new ExitException(1);
        }
        System.setProperty(MULTIMODULE_PROJECT_DIRECTORY, cliRequest.multiModuleProjectDirectory.toString());

        //
        // Make sure the Maven home directory is an absolute path to save us from confusion with say drive-relative
        // Windows paths.
        //
        String mvndHome = System.getProperty("mvnd.home");
        if (mvndHome != null) {
            System.setProperty("mvnd.home", new File(mvndHome).getAbsolutePath());
            System.setProperty("maven.home", new File(mvndHome + "/mvn").getAbsolutePath());
        }

        EnvHelper.environment(cliRequest.workingDirectory, clientEnv, slf4jLogger::warn);
    }

    protected void cli(CliRequest cliRequest)
            throws Exception {
        CLIManager cliManager = newCLIManager();

        List<String> args = new ArrayList<>();
        CommandLine mavenConfig = null;
        try {
            File configFile = new File(cliRequest.multiModuleProjectDirectory, MVN_MAVEN_CONFIG);

            if (configFile.isFile()) {
                for (String arg : new String(Files.readAllBytes(configFile.toPath())).split("\\s+")) {
                    if (!arg.isEmpty()) {
                        args.add(arg);
                    }
                }

                mavenConfig = cliManager.parse(args.toArray(new String[0]));
                List<?> unrecongized = mavenConfig.getArgList();
                if (!unrecongized.isEmpty()) {
                    throw new ParseException("Unrecognized maven.config entries: " + unrecongized);
                }
            }
        } catch (ParseException e) {
            buildEventListener.log("Unable to parse maven.config: " + e.getMessage());
            buildEventListener.log("Run 'mvnd --help' for available options.");
            throw new ExitException(1);
        }

        try {
            if (mavenConfig == null) {
                cliRequest.commandLine = cliManager.parse(cliRequest.args);
            } else {
                cliRequest.commandLine = cliMerge(cliManager.parse(cliRequest.args), mavenConfig);
            }
        } catch (ParseException e) {
            buildEventListener.log("Unable to parse command line options: " + e.getMessage());
            buildEventListener.log("Run 'mvnd --help' for available options.");
            throw new ExitException(1);
        }
    }

    protected void help(CliRequest cliRequest) throws Exception {
        if (cliRequest.commandLine.hasOption(CLIManager.HELP)) {
            buildEventListener.log(MvndHelpFormatter.displayHelp(newCLIManager()));
            throw new ExitException(0);
        }
    }

    protected CLIManager newCLIManager() {
        CLIManager cliManager = new CLIManager();
        cliManager.options.addOption(Option.builder(RESUME).longOpt("resume").desc("Resume reactor from " +
                "the last failed project, using the resume.properties file in the build directory").build());
        cliManager.options.addOption(Option.builder().longOpt(RAW_STREAMS).desc("Do not decorate output and " +
                "error streams").build());
        return cliManager;
    }

    /**
     * configure logging
     */
    protected void logging(CliRequest cliRequest) {
        super.logging(cliRequest);

        LoggerContext context = (LoggerContext) slf4jLoggerFactory;
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        try {
            context.getLoggerList().stream()
                    .filter(l -> l != rootLogger)
                    .forEach(l -> l.setLevel(null));
            new ContextInitializer(context).autoConfig();
        } catch (Exception e) {
            throw new RuntimeException("Error configuring logging", e);
        }
        if (cliRequest.verbose) {
            rootLogger.setLevel(Level.DEBUG);
        } else if (cliRequest.quiet) {
            rootLogger.setLevel(Level.ERROR);
        }

        // Workaround for https://github.com/mvndaemon/mvnd/issues/39
        Level level = Level.toLevel(System.getProperty("mvnd.log.level"), null);
        if (level != null) {
            Logger mvndLogger = context.getLogger("org.mvndaemon.mvnd");
            mvndLogger.setLevel(level);
        }

        // LOG STREAMS
        if (!cliRequest.commandLine.hasOption(CLIManager.LOG_FILE)
                && !cliRequest.commandLine.hasOption(RAW_STREAMS)) {
            Logger stdout = context.getLogger("stdout");
            Logger stderr = context.getLogger("stderr");
            stdout.setLevel(Level.INFO);
            stderr.setLevel(Level.INFO);
            System.setOut(new LoggingOutputStream(s -> stdout.info("[stdout] " + s)).printStream());
            System.setErr(new LoggingOutputStream(s -> stderr.warn("[stderr] " + s)).printStream());
        }
    }

    @Override
    protected void printOut(String message) {
        buildEventListener.log(message);
    }

    @Override
    protected void printErr(String message) {
        buildEventListener.log(message);
    }

    @Override
    protected void showError(String message, Throwable e, boolean showStackTrace) {
        buildEventListener.fail(e);
    }

    protected PlexusContainer container(CliRequest cliRequest) {
        Map<String, Object> data = new HashMap<>();
        data.put("plexus", container);
        data.put("workingDirectory", cliRequest.workingDirectory);
        data.put("systemProperties", cliRequest.systemProperties);
        data.put("userProperties", cliRequest.userProperties);
        data.put("versionProperties", CLIReportingUtils.getBuildProperties());
        eventSpyDispatcher.init(() -> data);
        return null;
    }

    protected List<File> parseExtClasspath(CliRequest cliRequest) {
        return Stream
                .of(Environment.MVND_EXT_CLASSPATH.asString().split(","))
                .map(File::new)
                .collect(Collectors.toList());
    }

    @Override
    protected void populateFromContainer(CliRequest cliRequest, PlexusContainer container) {
    }

    @Override
    protected AbstractModule createModule(CoreExports exports) {
        return new AbstractModule() {
            @Override
            protected void configure() {
                bind(ILoggerFactory.class).toInstance(slf4jLoggerFactory);
                bind(CoreExports.class).toInstance(exports);
                bind(ExtensionRealmCache.class).to(InvalidatingExtensionRealmCache.class);
                bind(PluginArtifactsCache.class).to(InvalidatingPluginArtifactsCache.class);
                bind(PluginRealmCache.class).to(InvalidatingPluginRealmCache.class);
                bind(ProjectArtifactsCache.class).to(InvalidatingProjectArtifactsCache.class);
                bind(MavenPluginManager.class).to(CliMavenPluginManager.class);
                bind(PluginVersionResolver.class).to(CachingPluginVersionResolver.class);
            }
        };
    }

    @Override
    protected List<CoreExtension> loadCoreExtensionsDescriptors(File multiModuleProjectDirectory) {
        return Stream
                .of(Environment.MVND_CORE_EXTENSIONS.asString().split(";"))
                .filter(s -> s != null && !s.isEmpty())
                .map(s -> {
                    String[] parts = s.split(":");
                    CoreExtension ce = new CoreExtension();
                    ce.setGroupId(parts[0]);
                    ce.setArtifactId(parts[1]);
                    ce.setVersion(parts[2]);
                    return ce;
                })
                .collect(Collectors.toList());
    }

    //
    // This should probably be a separate tool and not be baked into Maven.
    //
    @Override
    protected void encryption(CliRequest cliRequest) {
        // TODO
        if (cliRequest.commandLine.hasOption(CLIManager.ENCRYPT_MASTER_PASSWORD)) {
            throw new UnsupportedOperationException("Unsupported option: " + CLIManager.ENCRYPT_MASTER_PASSWORD);
        } else if (cliRequest.commandLine.hasOption(CLIManager.ENCRYPT_PASSWORD)) {
            throw new UnsupportedOperationException("Unsupported option: " + CLIManager.ENCRYPT_PASSWORD);
        }
    }

    @Override
    protected MavenExecutionResult doExecute(MavenExecutionRequest request) {
        slf4jLogger.info(buffer().a("Processing build on daemon ").strong(Environment.MVND_ID.asString()).toString());

        MavenExecutionResult result = maven.execute(request);

        LoggingOutputStream.forceFlush(System.out);
        LoggingOutputStream.forceFlush(System.err);
        return result;
    }

    @Override
    protected void addEnvVars(Properties props) {
        if (props != null) {
            boolean caseSensitive = Os.current() == Os.WINDOWS;
            for (Map.Entry<String, String> entry : clientEnv.entrySet()) {
                String key = "env." + (caseSensitive ? entry.getKey() : entry.getKey().toUpperCase(Locale.ENGLISH));
                props.setProperty(key, entry.getValue());
            }
        }
    }

    @Override
    protected TransferListener determineTransferListener(
            boolean quiet, boolean verbose, CommandLine commandLine, MavenExecutionRequest request) {
        return new DaemonMavenTransferListener(buildEventListener, new Slf4jMavenTransferListener());
    }

    @Override
    protected ExecutionListener determineExecutionListener() {
        try {
            LoggingExecutionListener executionListener = container.lookup(LoggingExecutionListener.class);
            ExecutionEventLogger executionEventLogger = new ExecutionEventLogger();
            executionListener.init(
                    eventSpyDispatcher.chainListener(executionEventLogger),
                    buildEventListener);
            return executionListener;
        } catch (ComponentLookupException e) {
            throw new IllegalStateException("Could not determine execution listener", e);
        }
    }
}
