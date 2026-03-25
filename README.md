# gdrive-mcp-server

A **Google Drive MCP Server** built with [Spring AI MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html).

Exposes Google Drive operations as MCP tools, making your Google Drive accessible to any MCP-compatible AI client (Claude Desktop, GitHub Copilot, Cursor, etc.).

---

## Tools

| Tool | Description |
|---|---|
| `list_files` | List files/folders, optionally filtered by parent folder |
| `search_files` | Search by name or full-text content |
| `get_file_info` | Get metadata for a specific file by ID |
| `read_file` | Read content of a Google Doc, Sheet, Slides, or text file |
| `list_folders` | List all folders or sub-folders within a parent |
| `create_document` | Create a new empty Google Doc |
| `create_spreadsheet` | Create a new empty Google Sheet |
| `create_presentation` | Create a new empty Google Slides presentation |
| `create_folder` | Create a new folder |
| `update_document` | Write (replace) the text content of an existing Google Doc |
| `update_spreadsheet` | Write (replace) the content of an existing Google Sheet using CSV data |

---

## Prerequisites

- Java 21+
- Maven 3.9+
- A Google Cloud project with the **Google Drive API** enabled
- OAuth2 credentials stored in `~/.config/google-drive-mcp/`

### Getting OAuth2 credential files

1. Go to [Google Cloud Console](https://console.cloud.google.com) → **APIs & Services** → **Credentials**
2. Create an **OAuth 2.0 Client ID** (Desktop application) and download it as `gcp-oauth.keys.json`
3. Place it at `~/.config/google-drive-mcp/gcp-oauth.keys.json`
4. Use the [OAuth 2.0 Playground](https://developers.google.com/oauthplayground) (or `gcloud auth`) to generate tokens and save as `~/.config/google-drive-mcp/tokens.json`

The `tokens.json` file must contain:
```json
{
  "client_id": "...",
  "client_secret": "...",
  "refresh_token": "...",
  "access_token": "...",
  "token_type": "Bearer"
}
```

---

## Configuration

Set the path to your tokens file via the `GDRIVE_TOKENS_PATH` environment variable:

```bash
export GDRIVE_TOKENS_PATH=~/.config/google-drive-mcp/tokens.json
```

---

## Build & Run

```bash
mvn clean package -DskipTests
java -jar target/gdrive-mcp-server-0.0.1-SNAPSHOT.jar
```

---

## Connect to an MCP client

### GitHub Copilot CLI

The Copilot CLI stores MCP server config in `~/.copilot/mcp-config.json`.

**Option A — interactive (recommended):**
```
/mcp add
```
Fill in the fields and press `Ctrl+S` to save.

**Option B — edit config file directly:**

Add to `~/.copilot/mcp-config.json`:

```json
{
  "mcpServers": {
    "google-drive": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/path/to/gdrive-mcp-server-0.0.1-SNAPSHOT.jar"],
      "env": {
        "GDRIVE_TOKENS_PATH": "~/.config/google-drive-mcp/tokens.json"
      },
      "tools": ["*"]
    }
  }
}
```

### GitHub Copilot in VS Code

Add to `.vscode/mcp.json` in your workspace (or user-level `settings.json` under `"mcp"`):

```json
{
  "servers": {
    "google-drive": {
      "type": "stdio",
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

### Cursor

Add to `~/.cursor/mcp.json`:

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

---

## Project Structure

```
src/main/java/io/github/mahditilab/gdrivemcp/
  GdriveMcpServerApplication.java   # Spring Boot entry point
  config/
    GoogleDriveConfig.java           # Google Drive API client setup (OAuth2)
  tools/
    GoogleDriveTools.java            # MCP tools (@Tool annotations)
src/main/resources/
  application.yml                    # MCP server config (STDIO transport)
```

---

## Tech Stack

- **Java 21**
- **Spring Boot 3.4**
- **Spring AI MCP Server Boot Starter** (`spring-ai-starter-mcp-server`) — STDIO transport
- **Google Drive API v3** (`google-api-services-drive`)
- **Google Auth Library** (`google-auth-library-oauth2-http`)
