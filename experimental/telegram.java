///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.5.0
//DEPS com.github.pengrad:java-telegram-bot-api:5.0.1

/*
 * A very simple test script for receiving and sending messages on Telegram.
 * You'll need to set the TELEGRAM_TOKEN environment variable for things to work.
 * How to create a token: https://core.telegram.org/bots/api#authorizing-your-bot
 * 
 * NB: It's NOT possible to instigate a conversation with a user from a Bot,
 * the user MUST first have contacted the Bot. Only from that moment onward is
 * the Bot allowed to send messages to that particular user.
 * 
 * For that purpose we can first run this script with `jbang telegram.java receive`.
 * This wil sit waiting for users to connect to it. When that happens a "/start"
 * message will be received with a Conversation ID. That ID can then be used to
 * send messages usinig `jbang telegram.java send <conversation-id> <message>`.
 */

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;

@Command(name = "telegram", mixinStandardHelpOptions = true, version = "telegram 0.1",
        description = "Telegram bot made with jbang", subcommands = { Receive.class, Send.class })
class telegram {
    String token;

    telegram(String token) {
        this.token = token;
    }

    public static void main(String... args) {
        String token = System.getenv("TELEGRAM_TOKEN");
        if (token == null || token.isEmpty()) {
            System.err.println("You must set TELEGRAM_TOKEN environment variable. Aborting.");
            System.exit(1);
        }
        int exitCode = new CommandLine(new telegram(token)).execute(args);
        System.exit(exitCode);
    }
}

@Command(name = "receive", mixinStandardHelpOptions = true, description = "Receive events")
class Receive implements Callable<Integer> {

    @ParentCommand
    telegram parent;

    @Override
    public Integer call() throws Exception {

        TelegramBot bot = new TelegramBot.Builder(parent.token).build();

        bot.setUpdatesListener(updates -> {
            updates.forEach(update -> {
                //System.out.println(update);
                long chatId = update.message().chat().id();
                String from = update.message().from().username();
                String msg = update.message().text();
                System.out.println("Received message '" + msg + "' from " + from + " (conversation: " + chatId + ")");
                SendResponse response = bot.execute(new SendMessage(chatId, "Received: '" + msg + "'"));
            });
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        });
        
        synchronized (telegram.class) {
            telegram.class.wait();
        }

        return 0;
    }
}

@Command(name = "send", mixinStandardHelpOptions = true, description = "Send a message")
class Send implements Callable<Integer> {
    @ParentCommand
    telegram parent;

    @Parameters(index = "0", description = "The chat ID to use to send the message")
    private long chatId;

    @Parameters(index = "1", description = "The message to send", defaultValue = "Hello!")
    private String msg;

    @Override
    public Integer call() throws Exception {

        TelegramBot bot = new TelegramBot.Builder(parent.token).build();

        SendResponse response = bot.execute(new SendMessage(chatId, msg));
        if (response.isOk()) {
            System.out.println("Message sent okay.");
            return 0;
        } else {
            System.out.println("Message could not be sent! " + response.errorCode() + " " + response.description());
            return 1;
        }
    }
}
