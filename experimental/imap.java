///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS info.picocli:picocli:4.7.7
//DEPS com.sun.mail:jakarta.mail:1.6.8
//DEPS com.google.code.gson:gson:2.8.6

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.UIDFolder;
import javax.mail.event.MessageCountAdapter;
import javax.mail.event.MessageCountEvent;
import javax.mail.internet.MimeBodyPart;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;
import com.sun.mail.imap.IdleManager;

@Command(name = "imap", mixinStandardHelpOptions = true, version = "imap 0.1",
        description = "imap made with jbang - prints out unread message count.", showDefaultValues = true)
class imap implements Callable<Integer> {

    @CommandLine.Option(names={"--host"}, defaultValue = "imap.gmail.com")
    private String host;

    @Option(names={"--username", "-u"}, required = true)
    private String username;

    @Option(names = {"--password", "-p"}, required = true)
    private String password;

    @Option(names = {"--isanswered" }, description = "Include messages that have been answered")
    private Boolean isAnswered;

    @Option(names = {"--isdeleted" }, description = "Include messages that are deleted")
    private Boolean isDeleted;

    @Option(names = {"--isdraft" }, description = "Include messages that are drafts")
    private Boolean isDraft;

    @Option(names = {"--isflagged" }, description = "Include messages that are flagged")
    private Boolean isFlagged;

    @Option(names = {"--isrecent" }, description = "Include messages that are recent")
    private Boolean isRecent;

    @Option(names = {"--isseen" }, description = "Include messages that are read")
    private Boolean isSeen;

    @Option(names = {"--hasattachments" }, description = "Include messages that have attachments")
    private Boolean hasAttachments;

    @Option(names = {"--subject-contains", "-s"}, description = "Include messages whose subject contain the given text")
    private String subjectContains;

    @Option(names = {"--subject-matches", "-S"}, description = "Include messages whose subject matches the given expression")
    private Pattern subjectMatches;

    @Option(names = {"--print"}, description = "Print information about the messages")
    private boolean print;

    @Option(names = {"--save"}, description = "Save messages to files")
    private boolean save;

    @Option(names = {"--output"}, description = "Output folder to write messages to")
    private Path output;

    @Option(names = {"--watch", "-w"}, description = "Keep watching for incoming messages")
    private boolean watch;

    @Parameters(index="0", defaultValue="INBOX")
    String folder;

    public static void main(String... args) {
        int exitCode = new CommandLine(new imap()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        Properties props = System.getProperties();
        props.setProperty("mail.store.protocol", "imaps");
        Session session = Session.getDefaultInstance(props, null);
        ExecutorService es = Executors.newCachedThreadPool();
        Properties sprops = session.getProperties();
        sprops.put("mail.event.scope", "session"); // or "application"
        sprops.put("mail.event.executor", es);
        sprops.put("mail.imaps.usesocketchannels", true);
        Store store = session.getStore("imaps");
        store.connect(host, username, password);

        Folder f = store.getFolder(folder);
        f.open(Folder.READ_ONLY);
        System.out.println(f.getName() + ":" + f.getUnreadMessageCount());
        filterMessages(f.getMessages()).forEach(m -> processMessage(m));

        if (watch) {
            IdleManager idleManager = new IdleManager(session, es);
            f.addMessageCountListener(new MessageCountAdapter() {
                public void messagesAdded(MessageCountEvent ev) {
                    Folder folder = (Folder)ev.getSource();
                    Message[] msgs = ev.getMessages();
                    System.out.println("Folder: " + folder +
                        " got " + msgs.length + " new messages");
                    filterMessages(msgs).forEach(m -> processMessage(m));
                    try {
                        // keep watching for new messages
                        System.out.println("Waiting for messages...");
                        idleManager.watch(folder);
                    } catch (MessagingException mex) {
                        // ignore
                    }
                }
            });
            System.out.println("Waiting for messages...");
            idleManager.watch(f);
            
            es.awaitTermination(1000, TimeUnit.DAYS);
        }
        
        return f.getUnreadMessageCount();
    }

    private List<Message> filterMessages(Message[] msgs) {
        return filterMessages(Arrays.stream(msgs)).collect(Collectors.toList());
    }

    private Stream<Message> filterMessages(Stream<Message> msgs) {
        if (isAnswered != null) {
            msgs = msgs.filter(msg -> isSet(msg, Flags.Flag.ANSWERED) == isAnswered);
        }
        if (isDeleted != null) {
            msgs = msgs.filter(msg -> isSet(msg, Flags.Flag.DELETED) == isDeleted);
        }
        if (isDraft != null) {
            msgs = msgs.filter(msg -> isSet(msg, Flags.Flag.DRAFT) == isDraft);
        }
        if (isFlagged != null) {
            msgs = msgs.filter(msg -> isSet(msg, Flags.Flag.FLAGGED) == isFlagged);
        }
        if (isRecent != null) {
            msgs = msgs.filter(msg -> isSet(msg, Flags.Flag.RECENT) == isRecent);
        }
        if (isSeen != null) {
            msgs = msgs.filter(msg -> isSet(msg, Flags.Flag.SEEN) == isSeen);
        }
        if (subjectContains != null) {
            msgs = msgs.filter(msg -> getSubject(msg).contains(subjectContains));
        }
        if (subjectMatches != null) {
            msgs = msgs.filter(msg -> subjectMatches.matcher(getSubject(msg)).find());
        }
        if (hasAttachments != null) {
            msgs = msgs.filter(msg -> hasAttachments(msg));
        }
        return msgs;
    }

    private boolean isSet(Message m, Flags.Flag f) {
        try {
            return m.isSet(f);
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    private String getSubject(Message m) {
        try {
            return m.getSubject();
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean hasAttachments(Part part) {
        try {
            if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                return true;
            }
            if (part.getContentType().contains("multipart")) {
                List<Map<String, Object>> parts = new ArrayList<>();
                Multipart multiPart = (Multipart)part.getContent();
                for (int i = 0; i < multiPart.getCount(); i++) {
                    MimeBodyPart p = (MimeBodyPart)multiPart.getBodyPart(i);
                    if (hasAttachments(p)) {
                        return true;
                    }
                }
            }
        } catch (MessagingException|IOException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    private void processMessage(Message msg) {
        Map<String, Object> jsonObject = new HashMap<>();
        long uid = messageUID(msg);
        Path msgDir = output.resolve("msg_" + uid);
        if (save) {
            msgDir.toFile().mkdirs();
        }
        processMessage(msg, 1, jsonObject, msgDir);
        if (save) {
            Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
            Path jsonFile = msgDir.resolve("message.json");
            try (FileWriter writer = new FileWriter(jsonFile.toFile())) {
                gson.toJson(jsonObject, writer);
            } catch (IOException ex) {
                System.err.println("Unable to write message details file for " + uid);
            }
            System.out.println("Message #" + uid + " written to " + msgDir);
        }
    }

    private void processMessage(Part part, int depth, Map<String, Object> jsonObject, Path msgDir) {
        try {
            if (print) {
                printMessage(part, depth);
            }
            if (save) {
                saveMessage(part, jsonObject, msgDir);
            }
            if (part.getContentType().contains("multipart")) {
                List<Map<String, Object>> parts = new ArrayList<>();
                jsonObject.put("parts", parts);
                Multipart multiPart = (Multipart)part.getContent();
                for (int i = 0; i < multiPart.getCount(); i++) {
                    MimeBodyPart p = (MimeBodyPart)multiPart.getBodyPart(i);
                    Map<String, Object> partObject = new HashMap<>();
                    processMessage(p, depth + 1, partObject, msgDir);
                    parts.add(partObject);
                }
            }
        } catch (MessagingException|IOException mex) {
            // Ignore
        }
    }

    private void printMessage(Part part, int depth) throws MessagingException, IOException {
        String idt = indent(depth);
        if (part instanceof Message) {
            Message msg = (Message)part;
            long uid = ((UIDFolder)msg.getFolder()).getUID(msg);
            String dt = DateTimeFormatter.ISO_INSTANT.format(msg.getReceivedDate().toInstant());
            System.out.println(idt + "Msg: " + uid);
            System.out.println(idt + "   Subject: " + msg.getSubject());
            System.out.println(idt + "   Received: " + dt);
        } else {
            System.out.println(idt + "Part:");
        }
        System.out.println(idt + "   Content-Type: " + part.getContentType().replaceAll("[\t\n\r]+", " "));
        if (part.getDisposition() != null) {
            System.out.println(idt + "   Disposition: " + part.getDisposition());
        }
        boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
        if (isAttachment) {
            System.out.println(idt + "   Filename: " + part.getFileName());
        }
        String contentType = part.getContentType().toLowerCase();
        if (contentType.contains("text/")) {
            System.out.println(idt + "   Content: " + part.getContent().toString().substring(0, 64).replaceAll("[\t\n\r]+", " ") + "...");
        }
    }

    private String indent(int depth) {
        return String.join("", Collections.nCopies(depth, "   ").toArray(new String[0]));
    }

    private long messageUID(Message msg) {
        try {
            return ((UIDFolder) msg.getFolder()).getUID(msg);
        } catch (MessagingException e) {
            return -1;
        }
    }

    private void saveMessage(Part part, Map<String, Object> jsonObject, Path msgDir) throws MessagingException,
            IOException {
        if (part instanceof Message) {
            Message msg = (Message)part;
            long uid = ((UIDFolder)msg.getFolder()).getUID(msg);
            String dt = DateTimeFormatter.ISO_INSTANT.format(msg.getReceivedDate().toInstant());
            jsonObject.put("uid", uid);
            jsonObject.put("subject", msg.getSubject());
            jsonObject.put("received", dt);
        }
        jsonObject.put("contentType", part.getContentType().replaceAll("[ \t\n\r]", ""));
        if (part.getDisposition() != null) {
            jsonObject.put("disposition", part.getDisposition());
        }
        boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
        String contentType = part.getContentType().toLowerCase();
        if (isAttachment || contentType.contains("text/")) {
            String fileName = part.getFileName() != null ? part.getFileName() : "content." + extension(contentType);
            jsonObject.put("fileName", fileName);
            if (part instanceof MimeBodyPart) {
                Path file;
                if (isAttachment) {
                    file = Paths.get("attachments").resolve(fileName);
                    file.toFile().mkdirs();
                    jsonObject.put("isAttachment", true);
                } else {
                    file = Paths.get(fileName);
                }
                jsonObject.put("filePath", file.toString());
                ((MimeBodyPart)part).saveFile(msgDir.resolve(file).toFile());
            }
        }
    }

    private String extension(String type) {
        Pattern p = Pattern.compile("text/([\\w]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(type);
        if (m.find() && m.groupCount() > 0) {
            String ext = m.group(1).toLowerCase();
            if ("plain".equals(ext)) {
                ext = "txt";
            }
            return ext;
        } else {
            return "unknown";
        }
    }
}
