/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jclouds.cli.runner;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import jline.Terminal;

import org.apache.felix.gogo.commands.Action;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.CommandException;
import org.apache.felix.gogo.commands.basic.AbstractCommand;
import org.apache.felix.gogo.runtime.CommandNotFoundException;
import org.apache.felix.gogo.runtime.CommandProcessorImpl;
import org.apache.felix.gogo.runtime.threadio.ThreadIOImpl;
import org.apache.felix.service.command.CommandSession;
import org.apache.felix.service.command.Function;
import org.apache.karaf.shell.console.NameScoping;
import org.apache.karaf.shell.console.jline.Console;
import org.apache.karaf.shell.console.jline.TerminalFactory;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;
import org.jclouds.blobstore.ContainerNotFoundException;
import org.jclouds.blobstore.KeyNotFoundException;
import org.jclouds.rest.AuthorizationException;
import org.jclouds.rest.InsufficientResourcesException;
import org.jclouds.rest.ResourceAlreadyExistsException;
import org.jclouds.rest.ResourceNotFoundException;
import org.jclouds.util.Throwables2;

import com.google.common.collect.Lists;

/**
 * This is forked from Apache Karaf and aligned to the needs of jclouds cli.
 */
public class Main {
    private static final String KARAF_HOME = "karaf.home";
    private static final Class[] parameters = new Class[] {URL.class};

    private String application = System.getProperty("karaf.name", "root");
    private String user = "karaf";

    private static enum Errno {
        ENOENT(2),
        EIO(5),
        ENXIO(6),
        EACCES(13),
        EEXIST(17),
        ETIMEDOUT(110),
        EDQUOT(122),
        UNKNOWN(255);

        private final int errno;

        Errno(int errno) {
            this.errno = errno;
        }

        int getErrno() {
            return errno;
        }
    }

    /**
     * Exit if klass has the same type or sub-type as throwable or one of its
     * causes.  If exiting, emit message.
     */
    private static void exitIfThrowableMatches(Throwable throwable,
            Class klass, Errno errno, String message) {
        Throwable cause = Throwables2.getFirstThrowableOfType(throwable, klass);
        if (cause == null) {
            return;
        }
        System.err.format("%s: %s\n", message, cause.getMessage());
        System.exit(errno.getErrno());
    }

    public static void main(String args[]) throws Exception {
        Main main = new Main();
        try {
            main.run(args);
        } catch (CommandNotFoundException cnfe) {
            String str = Ansi.ansi()
                    .fg(Ansi.Color.RED)
                    .a("Command not found: ")
                    .a(Ansi.Attribute.INTENSITY_BOLD)
                    .a(cnfe.getCommand())
                    .a(Ansi.Attribute.INTENSITY_BOLD_OFF)
                    .fg(Ansi.Color.DEFAULT).toString();
            System.err.println(str);
            System.exit(Errno.UNKNOWN.getErrno());
        } catch (CommandException ce) {
            System.err.println(ce.getNiceHelp());
            System.exit(Errno.UNKNOWN.getErrno());
        } catch (Throwable t) {
            exitIfThrowableMatches(t, AuthorizationException.class, Errno.EACCES, "Authorization error");
            exitIfThrowableMatches(t, ContainerNotFoundException.class, Errno.ENOENT, "Container not found");
            // FileNotFoundException must precede IOException due to inheritance
            exitIfThrowableMatches(t, FileNotFoundException.class, Errno.ENOENT, "File not found");
            // UnknownHostException must precede IOException due to inheritance
            exitIfThrowableMatches(t, UnknownHostException.class, Errno.ENXIO, "Unknown host");
            exitIfThrowableMatches(t, IOException.class, Errno.EIO, "IO error");
            exitIfThrowableMatches(t, InsufficientResourcesException.class, Errno.EDQUOT, "Insufficient resources");
            exitIfThrowableMatches(t, KeyNotFoundException.class, Errno.ENOENT, "Blob not found");
            exitIfThrowableMatches(t, ResourceAlreadyExistsException.class, Errno.EEXIST, "Resource already exists");
            // ContainerNotFoundException and KeyNotFoundException must precede ResourceNotFoundException due to inheritance
            exitIfThrowableMatches(t, ResourceNotFoundException.class, Errno.ENOENT, "Resource not found");
            exitIfThrowableMatches(t, TimeoutException.class, Errno.ETIMEDOUT, "Timeout");
            t.printStackTrace();
            System.exit(Errno.UNKNOWN.getErrno());
        }
        // We must explicitly exit on success since we do not close
        // BlobStoreContext and ComputeServiceContext.
        System.exit(0);
    }

    /**
     * Use this method when the shell is being executed as a top level shell.
     *
     * @param args
     * @throws Exception
     */
    public void run(String args[]) throws Exception {

        ThreadIOImpl threadio = new ThreadIOImpl();
        threadio.start();

        ClassLoader cl = Main.class.getClassLoader();
        //This is a workaround for windows machines struggling with long class paths.
        loadJarsFromPath(new File(System.getProperty(KARAF_HOME), "system"));
        loadJarsFromPath(new File(System.getProperty(KARAF_HOME), "deploy"));
        CommandProcessorImpl commandProcessor = new CommandProcessorImpl(threadio);

        discoverCommands(commandProcessor, cl);

        InputStream in = unwrap(System.in);
        PrintStream out = wrap(unwrap(System.out));
        PrintStream err = wrap(unwrap(System.err));
        run(commandProcessor, args, in, out, err);
    }


    /**
     * Loads Jars found under the specified path.
     * @throws IOException
     */
    public void loadJarsFromPath(File path) throws IOException {
        LinkedList<File> dirs = Lists.newLinkedList();
        dirs.add(path);
        while (!dirs.isEmpty()) {
            for (File f : dirs.poll().listFiles()) {
                if (f.isDirectory()) {
                    dirs.add(f);
                } else if (f.isFile() && f.getAbsolutePath().endsWith(".jar") && !f.getAbsolutePath().contains("pax-logging")) {
                    //We make sure to exclude pax logging jars when running outside of OSGi, since we use external logging jars in that case.
                    addURL(f.toURI().toURL());
                }
            }
        }
    }

    public static void addURL(URL u) throws IOException {
        URLClassLoader sysloader = (URLClassLoader) ClassLoader.getSystemClassLoader();
        Class sysclass = URLClassLoader.class;

        try {
            Method method = sysclass.getDeclaredMethod("addURL", parameters);
            method.setAccessible(true);
            method.invoke(sysloader, new Object[] {u});
        } catch (Throwable t) {
            t.printStackTrace();
            throw new IOException("Error, could not add URL to system classloader");
        }

    }

    private void run(final CommandProcessorImpl commandProcessor, String[] args, final InputStream in, final PrintStream out, final PrintStream err) throws Exception {

        if (args.length > 0) {
            // Commands have the form: jclouds:category-action.
            List<String> commands = Lists.newArrayList(commandProcessor.getCommands());
            Collections.sort(commands);
            if (!commands.contains(args[0])) {
                final String INDENT = "    ";
                StringBuilder sb = new StringBuilder(
                      "Usage: jclouds {category} {action} {options/arguments}");

                // list commands
                sb.append("\n\nValid commands:");
                for (String command : commands) {
                    if (command.startsWith("jclouds:")) {
                        command = command.substring("jclouds:".length());
                        int index = command.indexOf('-');
                        String category = command.substring(0, index);
                        String action = command.substring(index + 1);
                        sb.append('\n').append(INDENT).append(category)
                            .append(' ').append(action);
                        // TODO: expose command descriptions
                    }
                }

                // list options
                sb.append("\n\nOptions:")
                    .append('\n').append(INDENT)
                    .append("--properties: File containing properties")
                    .append('\n').append(INDENT)
                    .append("--provider:   The id of the provider")
                    .append('\n').append(INDENT)
                    .append("--api:        The id of the api")
                    .append('\n').append(INDENT)
                    .append("--endpoint:   The endpoint")
                    .append('\n').append(INDENT)
                    .append("--identity:   The identity")
                    .append('\n').append(INDENT)
                    .append("--credential: The credential");

                throw new CommandNotFoundException(sb.toString());
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(" ");
                }
                sb.append(args[i]);
            }

            // Shell is directly executing a sub/command, we don't setup a terminal and console
            // in this case, this avoids us reading from stdin un-necessarily.
            CommandSession session = commandProcessor.createSession(in, out, err);
            session.put("USER", user);
            session.put("APPLICATION", application);
            session.put(NameScoping.MULTI_SCOPE_MODE_KEY, Boolean.toString(isMultiScopeMode()));
            session.execute(sb);
        } else {
            // We are going into full blown interactive shell mode.

            final TerminalFactory terminalFactory = new TerminalFactory();
            final Terminal terminal = terminalFactory.getTerminal();
            Console console = createConsole(commandProcessor, in, out, err, terminal);
            CommandSession session = console.getSession();
            session.put("USER", user);
            session.put("APPLICATION", application);
            session.put(NameScoping.MULTI_SCOPE_MODE_KEY, Boolean.toString(isMultiScopeMode()));
            session.put("#LINES", new Function() {
                public Object execute(CommandSession session, List<Object> arguments) throws Exception {
                    return Integer.toString(terminal.getHeight());
                }
            });
            session.put("#COLUMNS", new Function() {
                public Object execute(CommandSession session, List<Object> arguments) throws Exception {
                    return Integer.toString(terminal.getWidth());
                }
            });
            session.put(".jline.terminal", terminal);

            console.run();

            terminalFactory.destroy();
        }

    }

    /**
     * Allow sub classes of main to change the Console implementation used.
     *
     * @param commandProcessor
     * @param in
     * @param out
     * @param err
     * @param terminal
     * @return
     * @throws Exception
     */
    protected Console createConsole(CommandProcessorImpl commandProcessor, InputStream in, PrintStream out, PrintStream err, Terminal terminal) throws Exception {
        return new Console(commandProcessor, in, out, err, terminal, null, null);
    }

    /**
     * Sub classes can override so that their registered commands do not conflict with the default shell
     * implementation.
     *
     * @return
     */
    public String getDiscoveryResource() {
        return "META-INF/services/org/apache/karaf/shell/commands";
    }

    private void discoverCommands(CommandProcessorImpl commandProcessor, ClassLoader cl) throws IOException, ClassNotFoundException {
        Enumeration<URL> urls = cl.getResources(getDiscoveryResource());
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
            try {
                while (true) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    line = line.trim();
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    final Class<Action> actionClass = (Class<Action>) cl.loadClass(line);
                    Command cmd = actionClass.getAnnotation(Command.class);
                    Function function = new AbstractCommand() {
                        @Override
                        public Action createNewAction() {
                            try {
                                return ((Class<? extends Action>) actionClass).newInstance();
                            } catch (InstantiationException e) {
                                throw new RuntimeException(e);
                            } catch (IllegalAccessException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    };
                    addCommand(cmd, function, commandProcessor);
                }
            } finally {
                reader.close();
            }
        }
    }

    protected void addCommand(Command cmd, Function function, CommandProcessorImpl commandProcessor) {
        try {
            commandProcessor.addCommand(cmd.scope(), function, cmd.name());
        } catch (Exception e) {
        }
    }


    public String getApplication() {
        return application;
    }

    public void setApplication(String application) {
        this.application = application;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    /**
     * Returns whether or not we are in multi-scope mode.
     * <p/>
     * The default mode is multi-scoped where we prefix commands by their scope. If we are in single
     * scoped mode then we don't use scope prefixes when registering or tab completing commands.
     */
    public boolean isMultiScopeMode() {
        return true;
    }

    private static PrintStream wrap(PrintStream stream) {
        OutputStream o = AnsiConsole.wrapOutputStream(stream);
        if (o instanceof PrintStream) {
            return (PrintStream) o;
        } else {
            return new PrintStream(o);
        }
    }

    private static <T> T unwrap(T stream) {
        try {
            Method mth = stream.getClass().getMethod("getRoot");
            return (T) mth.invoke(stream);
        } catch (Throwable t) {
            return stream;
        }
    }

    private static List<URL> getFiles(File base) throws MalformedURLException {
        List<URL> urls = Lists.newArrayList();
        getFiles(base, urls);
        return urls;
    }

    private static void getFiles(File base, List<URL> urls) throws MalformedURLException {
        for (File f : base.listFiles()) {
            if (f.isDirectory()) {
                getFiles(f, urls);
            } else if (f.getName().endsWith(".jar")) {
                urls.add(f.toURI().toURL());
            }
        }
    }
}
