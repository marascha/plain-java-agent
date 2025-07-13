import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;
import java.io.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

public class DemoAgent {
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-20250514";
    private static final String SYSTEM_PROMPT =
            "You are an AI assistant especialized in sending emails. " +
                    "You ask users questions about email content. " +
                    "Examples: recipient`s name and email (mandatory), sender`s name (mandatory), category (appointment, meeting request...), style (casual, formal) and language))." +
                    "Feel free to ask more questions if required." +
                    "Use short and clear sentences. When you call a tool, communicate that to the user." +
                    "Very important: Always persist collected info using memory tool so that you can access it later if conversation history is empty." +
                    "Before sending emails, you have to double-check the content with user. Use the sentence: Should I send the email now?" +
                    "Don't accept unrelated queries. In case query is unrelated, remind the user that you can only send emails." +
                    "Tools you have access to:" +
                    "Memory Tool - Description: Key value store you can use as memory. Remember: <remember>key: value</remember>, Recall: <recall>key</recall>, Clear Memory: <clear-memory/>" +
                    "Email Tool - Description: email service. Format: <send-email>to@email.com | Subject | Message body</send-email> ";

    private final String apiKey;
    private final HttpClient client;
    private final List<Message> history = new ArrayList<>();
    private final Map<String, String> memory = new HashMap<>();
    private final String memoryFile;

    public DemoAgent(String apiKey, String memoryFile) {
        this.apiKey = apiKey;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.memoryFile = memoryFile;
        loadMemory();
    }

    public String sendMessage(String userMessage) throws Exception {
        history.add(new Message("user", userMessage));

        String json = String.format(
                "{\"model\":\"%s\",\"max_tokens\":1024,\"system\":\"%s\\n\\nMemory: %s\",\"messages\":[%s]}",
                MODEL, escapeJson(SYSTEM_PROMPT), escapeJson(getMemoryContext()),
                history.stream().map(m -> String.format("{\"role\":\"%s\",\"content\":\"%s\"}",
                        m.role, escapeJson(m.content))).collect(Collectors.joining(",")));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("API error: " + response.statusCode() + " " + response.body());
        }

        String content = parseResponse(response.body());
        content = processTools(content);
        history.add(new Message("assistant", content));
        return content;
    }

    public void clearHistory() {
        history.clear();
    }

    public void clearMemory() {
        memory.clear();
        saveMemory();
    }

    public void showMemory() {
        System.out.println("\n┌─ 🧠 Memory ─────────────────────────────────────────────────────────────────┐");
        if (memory.isEmpty()) {
            System.out.println("│ 💭 No memories stored                                                     │");
        } else {
            memory.forEach((k, v) -> System.out.printf("│ 🔑 %-15s: %-55s │%n", k, v.length() > 55 ? v.substring(0, 52) + "..." : v));
        }
        System.out.println("└───────────────────────────────────────────────────────────────────────────────┘");
    }

    public void showHistory() {
        System.out.println("\n┌─ 📜 Conversation History ──────────────────────────────────────────────────┐");
        if (history.isEmpty()) {
            System.out.println("│ 🚫 No history                                                             ");
        } else {
            history.forEach(m -> {
                String role = m.role.equals("user") ? "👤 You" : "🤖 LLM";
                String content = m.content.length() > 150 ? m.content.substring(0, 147) + "..." : m.content;
                System.out.printf("│ " + "\u001B[93m" + "%-8s: %-60s %n " + "\u001B[0m", role, content);
            });
        }
        System.out.println("└──────────────────────────────────────────────────────────────────────────────┘");
    }

    private static class Message {
        final String role, content;
        Message(String role, String content) { this.role = role; this.content = content; }
    }

    private String processTools(String response) {
        // Memory tools
        response = Pattern.compile("<remember>(.*?)</remember>").matcher(response)
                .replaceAll(match -> {
                    String[] parts = match.group(1).split(":", 2);
                    if (parts.length == 2) {
                        memory.put(parts[0].trim().toLowerCase(), parts[1].trim());
                        saveMemory();
                        return "[💾 Remembered: " + parts[0].trim() + "]";
                    }
                    return "[❌ Invalid memory format]";
                });

        response = Pattern.compile("<recall>(.*?)</recall>").matcher(response)
                .replaceAll(match -> memory.getOrDefault(match.group(1).trim().toLowerCase(), "[🔍 Not found]"));

        response = Pattern.compile("<clear-memory/>").matcher(response)
                .replaceAll(match -> {
                    memory.clear();
                    saveMemory();
                    return "[🗑️ Memory cleared]";
                });

        // Email tool
        response = Pattern.compile("<send-email>\n(.*?)\n</send-email>").matcher(response)
                .replaceAll(match -> {
                    String[] parts = match.group(1).split("\\|", 3);
                    if (parts.length >= 3) {
                        String to = parts[0].trim();
                        String subject = parts[1].trim();
                        String body = parts[2].trim();
                        return String.format("[📧 Email sent to %s - \n 📝 Subject: %s - \n 💌 Body: %s]", to, subject, body);
                    }
                    return "[❌ Invalid email format - use: to@email.com | Subject | Message]";
                });

        return response;
    }

    private String parseResponse(String json) {
        int start = json.indexOf("\"text\":\"") + 8;
        int end = json.indexOf("\"", start);
        while (end > 0 && json.charAt(end - 1) == '\\') end = json.indexOf("\"", end + 1);
        return json.substring(start, end).replace("\\\"", "\"").replace("\\n", "\n");
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private String getMemoryContext() {
        return memory.isEmpty() ? "No memories" :
                memory.entrySet().stream()
                        .map(e -> e.getKey() + ": " + e.getValue())
                        .collect(Collectors.joining(", "));
    }

    private void loadMemory() {
        try (Scanner scanner = new Scanner(new File(memoryFile))) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) memory.put(parts[0].trim(), parts[1].trim());
                }
            }
        } catch (FileNotFoundException ignored) {
        }
    }

    private void saveMemory() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(memoryFile))) {
            writer.println("# 💾 Memory - " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            memory.forEach((k, v) -> writer.println(k + "=" + v));
        } catch (IOException e) {
            System.err.println("❌ Error saving memory: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        String apiKey = System.getenv("CLAUDE_API_KEY");
        if (apiKey == null) {
            System.err.println("❌ Please set CLAUDE_API_KEY environment variable");
            System.exit(1);
        }

        DemoAgent agent = new DemoAgent(apiKey, "memory.txt");
        Scanner scanner = new Scanner(System.in);

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║ 🤖 Demo AI Agent - Email Assistant                                           ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║ 🎯 Commands: 'quit', 'history', 'clear', 'memory', 'forget'                  ║");
        System.out.println("║ 💡 Try: 'I want to send happy birthday wishes to a friend'                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");

        while (true) {
            System.out.print("\n\n─ 👤 " + "\u001B[93m" + "You ──────────────────────────────────────────────────────────────────────\n│ " + "\u001B[0m");
            String input = scanner.nextLine();

            switch (input.toLowerCase()) {
                case "quit":
                    scanner.close();
                    System.out.println("👋 Goodbye!");
                    return;
                case "clear":
                    agent.clearHistory();
                    System.out.println("🧹 History cleared.");
                    continue;
                case "memory":
                    agent.showMemory();
                    continue;
                case "forget":
                    agent.clearMemory();
                    System.out.println("🗑️ Memory cleared.");
                    continue;
                case "history":
                    agent.showHistory();
                    continue;
            }

            try {
                String response = agent.sendMessage(input);
                System.out.println("\n\n─ 🤖" + "\u001B[93m" + "LLM ───────────────────────────────────────────────────────────────" + "\u001B[0m");
                Arrays.stream(response.split("\n")).forEach(line ->
                        System.out.printf("\n│ " + line));
            } catch (Exception e) {
                System.err.println("❌ Error: " + e.getMessage());
            }
        }
    }
}