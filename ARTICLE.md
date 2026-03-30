# Building an MCP Server with Spring Boot and Spring AI — A Practical Guide

*How I gave GitHub Copilot access to Google Drive in an afternoon*

---

## The Problem

I was working with GitHub Copilot CLI as an AI agent to automate some internal workflows at work. At some point I needed the agent to read a Google Slides presentation, create a copy, rewrite it for a new audience, and update it — all without me lifting a finger.

Sounds simple. But the agent had no way to talk to Google Drive.

I looked for an existing MCP server. Nothing quite fit. So I built one — using **Spring Boot** and **Spring AI** — and in this article I'll show you exactly how to do the same for any API you need.

---

## What is MCP?

**Model Context Protocol (MCP)** is an open protocol introduced by Anthropic that standardizes how AI models communicate with external tools and data sources. Think of it as a USB-C standard for AI integrations — you build a server once, and any compatible AI client (GitHub Copilot, Claude Desktop, Cursor, VS Code) can use it.

An MCP server exposes **tools** — functions the AI can call, with typed parameters and descriptions. The AI decides when and how to call them based on the user's intent.

The architecture looks like this:

```
User → AI Agent (Copilot / Claude / Cursor)
              ↕ MCP Protocol
         MCP Server (your Spring Boot app)
              ↕
         External API (Google Drive, Jira, Slack, etc.)
```

---

## Why Spring Boot + Spring AI?

Spring AI added first-class MCP server support via `spring-ai-starter-mcp-server`. The integration is minimal boilerplate: you annotate plain Java methods with `@Tool`, Spring AI handles the protocol, JSON schema generation, and STDIO transport automatically.

No manual JSON-RPC, no schema writing, no protocol plumbing. Just Java.

---

## Project Setup

### Dependencies

```xml
<dependencies>
    <!-- Spring AI MCP Server (STDIO transport) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-mcp-server</artifactId>
    </dependency>

    <!-- Your API client dependencies here -->
    <!-- e.g. Google Drive API -->
    <dependency>
        <groupId>com.google.apis</groupId>
        <artifactId>google-api-services-drive</artifactId>
        <version>v3-rev20240914-2.0.0</version>
    </dependency>
</dependencies>
```

Add the Spring AI BOM for version management:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### application.yml

```yaml
spring:
  ai:
    mcp:
      server:
        name: google-drive-mcp-server
        version: 1.0.0
        transport: stdio
```

That's it for configuration. STDIO transport means the MCP client launches your JAR as a subprocess and communicates via stdin/stdout — no HTTP server needed.

---

## Writing Your First Tool

Here's the core concept. You create a `@Component` class, inject your API client, and annotate methods with `@Tool`:

```java
@Component
public class MyTools {

    @Tool(name = "say_hello", description = "Says hello to the given name")
    public String sayHello(
        @ToolParam(description = "The name to greet", required = true) String name
    ) {
        return "Hello, " + name + "!";
    }
}
```

Spring AI reads the annotations, generates a JSON schema for the parameters, and registers the tool with the MCP protocol. The AI agent sees the tool name and description and knows when to call it.

Then register it as a `ToolCallbackProvider` bean:

```java
@Bean
public ToolCallbackProvider toolCallbackProvider(MyTools myTools) {
    return MethodToolCallbackProvider.builder()
            .toolObjects(myTools)
            .build();
}
```

That's the entire framework. Everything else is just your business logic.

---

## Real Example: Google Drive MCP Server

Let me walk through how I built `gdrive-mcp-server` — a fully working MCP server that gives AI agents access to Google Drive.

### Authentication

Google Drive uses OAuth2. I load credentials from a `tokens.json` file and build the Drive client:

```java
@Configuration
public class GoogleDriveConfig {

    @Value("${google.drive.tokens-path}")
    private String tokensPath;

    @Bean
    public Drive googleDrive() throws GeneralSecurityException, IOException {
        var credentials = loadCredentials(Path.of(tokensPath));
        var scoped = credentials.createScoped(
            List.of(DriveScopes.DRIVE, SlidesScopes.PRESENTATIONS));

        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JacksonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(scoped))
                .setApplicationName("gdrive-mcp-server")
                .build();
    }

    private UserCredentials loadCredentials(Path path) throws IOException {
        var tokens = new ObjectMapper().readTree(Files.readString(path));
        return UserCredentials.newBuilder()
                .setClientId(tokens.get("client_id").asText())
                .setClientSecret(tokens.get("client_secret").asText())
                .setRefreshToken(tokens.get("refresh_token").asText())
                .build();
    }
}
```

### Reading Files

```java
@Tool(name = "read_file", description = """
        Read the text content of a Google Drive file.
        Supports Google Docs, Sheets, Slides, and plain text files.
        """)
public String readFile(
        @ToolParam(description = "The Google Drive file ID", required = true) String fileId
) throws IOException {
    File file = drive.files().get(fileId)
            .setFields("id, name, mimeType")
            .execute();

    String mimeType = file.getMimeType();
    var out = new ByteArrayOutputStream();

    if ("application/vnd.google-apps.document".equals(mimeType)) {
        drive.files().export(fileId, "text/plain").executeMediaAndDownloadTo(out);
    } else if ("application/vnd.google-apps.spreadsheet".equals(mimeType)) {
        drive.files().export(fileId, "text/csv").executeMediaAndDownloadTo(out);
    } else if ("application/vnd.google-apps.presentation".equals(mimeType)) {
        return readPresentation(fileId); // uses Slides API
    } else {
        drive.files().get(fileId).executeMediaAndDownloadTo(out);
    }

    return out.toString();
}
```

### Updating Google Docs

Updating a Google Doc is straightforward — upload plain text and the Drive API converts it:

```java
@Tool(name = "update_document", description = """
        Write (replace) the text content of an existing Google Doc.
        """)
public String updateDocument(
        @ToolParam(description = "The Google Drive file ID", required = true) String fileId,
        @ToolParam(description = "The new plain text content", required = true) String content
) throws IOException {
    ByteArrayContent media = ByteArrayContent.fromString("text/plain", content);
    File updated = drive.files().update(fileId, new File(), media)
            .setFields("id, name")
            .execute();
    return "Updated document: id=%s, name=%s".formatted(updated.getId(), updated.getName());
}
```

### Updating Google Slides — The Tricky Part

Here's where it gets interesting. Unlike Docs and Sheets, **the Drive API cannot convert plain text into a Google Slides presentation**. You'll get a `conversionUnsupportedConversionPath` error if you try.

You need the **Google Slides API** and its `batchUpdate` endpoint, which accepts a list of structured requests. This requires adding a separate dependency:

```xml
<dependency>
    <groupId>com.google.apis</groupId>
    <artifactId>google-api-services-slides</artifactId>
    <version>v1-rev20250401-2.0.0</version>
</dependency>
```

The strategy I used:
1. Delete all existing slides (except the first — the API requires at least one)
2. Create new slides with `TITLE_AND_BODY` layout
3. Re-fetch to get shape IDs
4. Insert text into title, body, and notes placeholders

Here's the core loop:

```java
for (int i = 0; i < updatedSlides.size(); i++) {
    Page slide = updatedSlides.get(i);
    SlideContent sc = slideContents.get(i);

    for (PageElement element : slide.getPageElements()) {
        if (element.getShape() == null
                || element.getShape().getPlaceholder() == null) continue;

        String type    = element.getShape().getPlaceholder().getType();
        String shapeId = element.getObjectId();

        String text = switch (type) {
            case "TITLE", "CENTERED_TITLE" -> sc.title();
            case "BODY", "SUBTITLE"        -> sc.body();
            default -> null;
        };

        if (text != null) {
            // Guard: only delete if shape already has text
            boolean hasText = element.getShape().getText() != null
                    && element.getShape().getText().getTextElements() != null
                    && !element.getShape().getText().getTextElements().isEmpty();

            if (hasText) {
                requests.add(new Request().setDeleteText(
                    new DeleteTextRequest()
                        .setObjectId(shapeId)
                        .setTextRange(new Range().setType("ALL"))));
            }
            if (!text.isEmpty()) {
                requests.add(new Request().setInsertText(
                    new InsertTextRequest()
                        .setObjectId(shapeId)
                        .setInsertionIndex(0)
                        .setText(text)));
            }
        }
    }
}
```

> **Gotcha:** If a shape has no existing text, `deleteText` with range `ALL` will throw a `400 Bad Request` because `startIndex == endIndex == 0`. Always check for existing text before deleting.

### Speaker Notes

Speaker notes live on a separate `notesPage` per slide. Each notes page has a `BODY` placeholder. Writing to it is the same pattern:

```java
if (slide.getSlideProperties() != null
        && slide.getSlideProperties().getNotesPage() != null) {
    Page notesPage = slide.getSlideProperties().getNotesPage();
    for (PageElement el : notesPage.getPageElements()) {
        if (el.getShape() == null
                || !"BODY".equals(el.getShape().getPlaceholder().getType())) continue;

        requests.add(new Request().setInsertText(
            new InsertTextRequest()
                .setObjectId(el.getObjectId())
                .setInsertionIndex(0)
                .setText(speakerNotes)));
    }
}
```

### The Text Format

To keep things simple for the AI, I designed a plain text format:

```
Slide Title
Slide body content line 1
Slide body content line 2
[notes]
Speaker notes go here

---
Second Slide Title
Second slide body
[notes]
Notes for second slide
```

- `---` on its own line separates slides
- `[notes]` starts the speaker notes section for that slide

The same format is used for both reading and writing, so the AI can read a presentation, modify it, and write it back in one flow.

---

## Connecting to an MCP Client

Build the JAR:

```bash
mvn clean package -DskipTests
```

### GitHub Copilot CLI

Add to `~/.copilot/mcp-config.json`:

```json
{
  "mcpServers": {
    "google-drive": {
      "command": "java",
      "args": ["-jar", "/path/to/gdrive-mcp-server-0.0.1-SNAPSHOT.jar"],
      "env": {
        "GDRIVE_TOKENS_PATH": "~/.config/google-drive-mcp/tokens.json"
      }
    }
  }
}
```

### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "google-drive": {
      "command": "java",
      "args": ["-jar", "/path/to/gdrive-mcp-server-0.0.1-SNAPSHOT.jar"],
      "env": {
        "GDRIVE_TOKENS_PATH": "~/.config/google-drive-mcp/tokens.json"
      }
    }
  }
}
```

Restart the client, and your tools are live. The AI now has Google Drive access.

---

## What the AI Can Do With It

Here's a real example of what became possible once the server was running. I asked Copilot CLI:

> *"Read this presentation, create a copy, and rewrite it as a developer onboarding guide."*

The agent:
1. Called `read_file` to extract all slide content and notes
2. Called `create_presentation` to make a new empty presentation
3. Called `update_presentation` with the rewritten content — 20 slides, structured and formatted

All in one prompt, no manual steps.

---

## Key Lessons

**1. Write clear `@Tool` descriptions.** The AI decides when to call your tool based on the description alone. Be explicit about what the tool does, what it accepts, and what it returns.

**2. Not all Google APIs are equal.** The Drive API is great for Docs and Sheets but can't update Slides. Know the limits of your upstream API before designing your tools.

**3. Guard against API edge cases.** The `deleteText` bug I hit — failing on empty shapes — is the kind of thing that only surfaces at runtime. Test with both empty and populated resources.

**4. Match your text format to how AIs think.** A simple, consistent plain text format (with clear delimiters like `---` and markers like `[notes]`) is much more reliable than complex JSON for AI-generated content.

**5. STDIO transport is dead simple.** No ports, no HTTP server, no authentication between client and server. The MCP client just spawns your JAR as a subprocess.

---

## Source Code

The full project is open source:
👉 **https://github.com/m-tilab/gdrive-mcp-server**

It includes all 12 tools, OAuth2 setup instructions, and configuration examples for Copilot CLI, Claude Desktop, VS Code, and Cursor.

---

## What's Next?

Some ideas I'm considering:
- Adding Google Sheets read support with named range queries
- Supporting file uploads (images, PDFs)
- Slide formatting via the Slides API (fonts, colors, layouts)

MCP is still young but the ecosystem is growing fast. Building your own server for any internal API — your company's CRM, your issue tracker, your internal wiki — is now a weekend project.

What would you build?

---

*Found this useful? Give it a clap and follow for more posts on AI agents, Spring Boot, and developer tooling.*
