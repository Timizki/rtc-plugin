package com.deluan.jenkins.plugins.rtc.client;

import com.deluan.jenkins.plugins.rtc.changelog.JazzChangeSet;
import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.ForkOutputStream;
import org.apache.commons.lang.StringUtils;

import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encapsulates the invocation of RTC's SCM Command Line Interface, "scm".
 *
 * @author Deluan Quintao
 */
public class JazzClient {
    protected static final Logger logger = Logger.getLogger(JazzClient.class.getName());

    private static final String DATE_FORMAT = "yyyy-MM-dd-HH:mm:ss";
    private static final String CONTRIBUTOR_FORMAT = "|{name}|{email}|";
    private final SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);


    private final ArgumentListBuilder base;
    private final Launcher launcher;
    private final TaskListener listener;
    private String repositoryLocation;
    private String workspaceName;
    private String streamName;
    private String username;
    private String password;
    private FilePath jobWorkspace;


    public JazzClient(Launcher launcher, TaskListener listener, FilePath jobWorkspace, String jazzExecutable,
                      String user, String password, String repositoryLocation,
                      String streamName, String workspaceName) {
        base = new ArgumentListBuilder(jazzExecutable);
        this.launcher = launcher;
        this.listener = listener;
        this.username = user;
        this.password = password;
        this.repositoryLocation = repositoryLocation;
        this.streamName = streamName;
        this.workspaceName = workspaceName;
        this.jobWorkspace = jobWorkspace;
    }

    private ArgumentListBuilder addAuthInfo(ArgumentListBuilder args) {
        if (StringUtils.isNotBlank(username)) {
            args.add("-u", username);
        }
        if (StringUtils.isNotBlank(password)) {
            args.add("-P", password);
        }

        return args;
    }

    public boolean hasChanges() throws IOException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("status");
        addAuthInfo(args);
        args.add("-C", "-w", "-n");
        args.add("-d");
        args.add(jobWorkspace);

        logger.log(Level.FINER, args.toStringWithQuote());

        ByteArrayOutputStream output = popen(args);
        try {
            String outputString = new String(output.toByteArray());
            return outputString.contains("    Entrada:"); //FIXME How to force english?!
        } finally {
            output.close();
        }
    }

    public boolean load() throws IOException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("load", workspaceName);
        addAuthInfo(args);
        args.add("-r", repositoryLocation);
        args.add("-d");
        args.add(jobWorkspace);
        args.add("-f");

        logger.log(Level.FINER, args.toStringWithQuote());

        return (joinWithPossibleTimeout(run(args), true, listener) == 0);
    }

    public boolean isLoaded() throws IOException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("history");
        addAuthInfo(args);
        args.add("-m", "1");
        args.add("-d");
        args.add(jobWorkspace);

        logger.log(Level.FINER, args.toStringWithQuote());

        return (joinWithPossibleTimeout(run(args), true, listener) == 0);
    }

    public boolean accept() throws IOException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("accept");
        addAuthInfo(args);
        args.add("-d");
        args.add(jobWorkspace);
        args.add("--flow-components", "-o", "-v");

        logger.log(Level.FINER, args.toStringWithQuote());

        return (joinWithPossibleTimeout(run(args), true, listener) == 0);
    }

    public void getChanges(File changeLog) throws IOException, InterruptedException {
        Map<String, JazzChangeSet> compareCmdResults;
        Map<String, JazzChangeSet> listCmdResults;

        compareCmdResults = compare();
        if (!compareCmdResults.isEmpty()) {
            listCmdResults = list(compareCmdResults.keySet());

            for (Map.Entry<String, JazzChangeSet> entry : compareCmdResults.entrySet()) {
                JazzChangeSet changeSet1 = entry.getValue();
                JazzChangeSet changeSet2 = listCmdResults.get(entry.getKey());
                changeSet1.copyItemsFrom(changeSet2);
            }
            format(compareCmdResults.values(), changeLog);
        }
    }

    private void format(Collection<JazzChangeSet> changeSetList, File changelogFile) throws IOException {
        PrintWriter writer = new PrintWriter(new FileWriter(changelogFile));
        writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        writer.println("<changelog>");
        for (JazzChangeSet changeSet : changeSetList) {
            writer.println(String.format("\t<changeset rev=\"%s\">", changeSet.getRev()));
            writer.println(String.format("\t\t<date>%s</date>", changeSet.getDateStr()));
            writer.println(String.format("\t\t<user>%s</user>", changeSet.getUser()));
            writer.println(String.format("\t\t<email>%s</email>", changeSet.getEmail()));
            writer.println(String.format("\t\t<comment>%s</comment>", changeSet.getMsg()));

            if (!changeSet.getItems().isEmpty()) {
                writer.println("\t\t<files>");
                for (JazzChangeSet.Item item : changeSet.getItems()) {
                    writer.println(String.format("\t\t\t<file action=\"%s\">%s</file>", item.getAction(), item.getPath()));
                }
                writer.println("\t\t</files>");
            }

            if (!changeSet.getWorkItems().isEmpty()) {
                writer.println("\t\t<workitems>");
                for (String workItem : changeSet.getWorkItems()) {
                    writer.println(String.format("\t\t\t<workitem>%s</workitem>", workItem));
                }
                writer.println("\t\t</workitems>");
            }
            writer.println("\t</changeset>");
        }
        writer.println("</changelog>");
        writer.close();
    }

    private Map<String, JazzChangeSet> compare() throws IOException, InterruptedException {
        Map<String, JazzChangeSet> result = new HashMap<String, JazzChangeSet>();

        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("compare");
        args.add("ws", workspaceName);
        args.add("stream", streamName);
        addAuthInfo(args);
        args.add("-r", repositoryLocation);
        args.add("-I", "s");
        args.add("-C", '"' + CONTRIBUTOR_FORMAT + '"');
        args.add("-D", "\"|" + DATE_FORMAT + "|\"");

        logger.log(Level.FINER, args.toStringWithQuote());

        BufferedReader in = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(popen(args).toByteArray())));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                JazzChangeSet changeSet = new JazzChangeSet();
                System.out.println(line);
                String[] parts = line.split("\\|");
                String rev = parts[0].trim().substring(1);
                rev = rev.substring(0, rev.length() - 1);
                changeSet.setRev(rev);
                changeSet.setUser(parts[1].trim());
                changeSet.setEmail(parts[2].trim());
                changeSet.setMsg(Util.xmlEscape(parts[3].trim()));
                try {
                    changeSet.setDate(sdf.parse(parts[4].trim()));
                } catch (ParseException e) {
                    logger.log(Level.WARNING, "Error parsing date '" + parts[4].trim() + "' for revision (" + rev + ")");
                }
                result.put(rev, changeSet);
            }
        } finally {
            in.close();
        }

        return result;
    }

    private Map<String, JazzChangeSet> list(Collection<String> changeSets) throws IOException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("list");
        args.add("changesets");
        addAuthInfo(args);
        args.add("-d");
        args.add(jobWorkspace);
        for (String changeSet : changeSets) {
            args.add(changeSet);
        }

        logger.log(Level.FINER, args.toStringWithQuote());

        Map<String, JazzChangeSet> result = new HashMap<String, JazzChangeSet>();

        BufferedReader in = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(popen(args).toByteArray())));

        try {
            String line;
            JazzChangeSet changeSet = null;
            Pattern startChangesetPattern = Pattern.compile("^\\s{2}\\((\\d+)\\)\\s*---[$]\\s*(\\D*)\\s+(.*)$");
            Pattern filePattern = Pattern.compile("^\\s{6}(.{5})\\s(\\S*)\\s+(.*)$");
            Pattern workItemPattern = Pattern.compile("^\\s{6}\\((\\d+)\\)\\s+(.*)$");
            Matcher matcher;

            while ((line = in.readLine()) != null) {

                if ((matcher = startChangesetPattern.matcher(line)).matches()) {
                    if (changeSet != null) {
                        result.put(changeSet.getRev(), changeSet);
                    }
                    changeSet = new JazzChangeSet();
                    changeSet.setRev(matcher.group(1));
                } else if ((matcher = filePattern.matcher(line)).matches()) {
                    assert changeSet != null;
                    String action = "edit";
                    String path = matcher.group(3).replaceAll("\\\\", "/").trim();
                    String flag = matcher.group(1).substring(2);
                    if ("a".equals(flag)) {
                        action = "added";
                    } else if ("d".equals(flag)) {
                        action = "deleted";
                    }
                    changeSet.addItem(Util.xmlEscape(path), action);
                } else if ((matcher = workItemPattern.matcher(line)).matches()) {
                    assert changeSet != null;
                    changeSet.addWorkItem(matcher.group(2));
                }
            }

            if (changeSet != null) {
                result.put(changeSet.getRev(), changeSet);
            }
        } finally {
            in.close();
        }

        return result;
    }


    private ArgumentListBuilder seed() {
        return base.clone();
    }

    private ProcStarter l(ArgumentListBuilder args) {
        // set the default stdout
        return launcher.launch().cmds(args).stdout(listener);
    }

    private ProcStarter run(String... args) {
        return l(seed().add(args));
    }

    private ProcStarter run(ArgumentListBuilder args) {
        return l(seed().add(args.toCommandArray()));
    }

    private int joinWithPossibleTimeout(ProcStarter proc, boolean useTimeout, final TaskListener listener) throws IOException, InterruptedException {
        return useTimeout ? proc.start().joinWithTimeout(60 * 5, TimeUnit.SECONDS, listener) : proc.join();
    }

    /**
     * Runs the command and captures the output.
     */
    private ByteArrayOutputStream popen(ArgumentListBuilder args)
            throws IOException, InterruptedException {

        PrintStream output = listener.getLogger();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ForkOutputStream fos = new ForkOutputStream(baos, output);
        if (joinWithPossibleTimeout(run(args).stdout(fos), true, listener) == 0) {
            return baos;
        } else {
            listener.error("Failed to run " + args.toStringWithQuote());
            throw new AbortException();
        }
    }


}
