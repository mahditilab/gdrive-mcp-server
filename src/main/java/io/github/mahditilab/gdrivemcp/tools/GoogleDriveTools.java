package io.github.mahditilab.gdrivemcp.tools;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.slides.v1.Slides;
import com.google.api.services.slides.v1.model.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class GoogleDriveTools {

    private static final String FIELDS = "id, name, mimeType, size, modifiedTime, webViewLink, parents";
    private static final String GOOGLE_DOC_MIME   = "application/vnd.google-apps.document";
    private static final String GOOGLE_SHEET_MIME = "application/vnd.google-apps.spreadsheet";
    private static final String GOOGLE_SLIDES_MIME = "application/vnd.google-apps.presentation";
    private static final String FOLDER_MIME       = "application/vnd.google-apps.folder";

    private final Drive drive;
    private final Slides slides;

    public GoogleDriveTools(Drive drive, Slides slides) {
        this.drive = drive;
        this.slides = slides;
    }

    @Tool(name = "list_files", description = """
            List files and folders in Google Drive.
            Optionally filter by a folder ID. Returns id, name, mimeType, size, modifiedTime and webViewLink.
            """)
    public String listFiles(
            @ToolParam(description = "Maximum number of files to return (default 20, max 100)", required = false) Integer pageSize,
            @ToolParam(description = "Parent folder ID to list files from. Omit for root/all files.", required = false) String folderId
    ) throws IOException {
        var request = drive.files().list()
                .setPageSize(pageSize != null ? Math.min(pageSize, 100) : 20)
                .setFields("files(" + FIELDS + ")")
                .setOrderBy("modifiedTime desc");

        if (folderId != null && !folderId.isBlank()) {
            request.setQ("'" + folderId + "' in parents and trashed = false");
        } else {
            request.setQ("trashed = false");
        }

        FileList result = request.execute();
        return formatFileList(result.getFiles());
    }

    @Tool(name = "search_files", description = """
            Search for files and folders in Google Drive by name or full-text content.
            Returns id, name, mimeType, modifiedTime and webViewLink for each match.
            """)
    public String searchFiles(
            @ToolParam(description = "Search query. E.g. 'name contains \"report\"' or 'fullText contains \"budget\"'", required = true) String query,
            @ToolParam(description = "Maximum number of results to return (default 20)", required = false) Integer pageSize
    ) throws IOException {
        FileList result = drive.files().list()
                .setQ(query + " and trashed = false")
                .setPageSize(pageSize != null ? Math.min(pageSize, 100) : 20)
                .setFields("files(" + FIELDS + ")")
                .setOrderBy("modifiedTime desc")
                .execute();

        return formatFileList(result.getFiles());
    }

    @Tool(name = "get_file_info", description = """
            Get metadata for a specific file or folder by its ID.
            Returns name, mimeType, size, modifiedTime, webViewLink and parent folder IDs.
            """)
    public String getFileInfo(
            @ToolParam(description = "The Google Drive file ID", required = true) String fileId
    ) throws IOException {
        File file = drive.files().get(fileId)
                .setFields(FIELDS)
                .execute();

        return formatFile(file);
    }

    @Tool(name = "read_file", description = """
            Read the text content of a Google Drive file.
            Supports Google Docs (exported as plain text), Google Sheets (exported as CSV),
            and plain text files. Binary files (images, videos) are not supported.
            """)
    public String readFile(
            @ToolParam(description = "The Google Drive file ID", required = true) String fileId
    ) throws IOException {
        File file = drive.files().get(fileId)
                .setFields("id, name, mimeType")
                .execute();

        String mimeType = file.getMimeType();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        if (GOOGLE_DOC_MIME.equals(mimeType)) {
            drive.files().export(fileId, "text/plain").executeMediaAndDownloadTo(out);
        } else if (GOOGLE_SHEET_MIME.equals(mimeType)) {
            drive.files().export(fileId, "text/csv").executeMediaAndDownloadTo(out);
        } else if (GOOGLE_SLIDES_MIME.equals(mimeType)) {
            drive.files().export(fileId, "text/plain").executeMediaAndDownloadTo(out);
        } else if (mimeType.startsWith("text/") || mimeType.equals("application/json")) {
            drive.files().get(fileId).executeMediaAndDownloadTo(out);
        } else {
            return "Cannot read file '%s' (mimeType: %s). Only Google Docs, Sheets, Slides, and plain text files are supported."
                    .formatted(file.getName(), mimeType);
        }

        return out.toString();
    }

    @Tool(name = "list_folders", description = """
            List all folders in Google Drive, or sub-folders within a specific parent folder.
            Returns id, name, modifiedTime and webViewLink for each folder.
            """)
    public String listFolders(
            @ToolParam(description = "Parent folder ID to list sub-folders from. Omit to list all folders.", required = false) String parentFolderId
    ) throws IOException {
        String query = "mimeType = '" + FOLDER_MIME + "' and trashed = false";
        if (parentFolderId != null && !parentFolderId.isBlank()) {
            query += " and '" + parentFolderId + "' in parents";
        }

        FileList result = drive.files().list()
                .setQ(query)
                .setPageSize(50)
                .setFields("files(id, name, modifiedTime, webViewLink, parents)")
                .setOrderBy("name")
                .execute();

        return formatFileList(result.getFiles());
    }

    @Tool(name = "create_document", description = """
            Create a new empty Google Doc in Google Drive.
            Returns the ID, name and webViewLink of the created document.
            """)
    public String createDocument(
            @ToolParam(description = "Name of the new document", required = true) String name,
            @ToolParam(description = "Parent folder ID where the document should be created. Omit for My Drive root.", required = false) String folderId
    ) throws IOException {
        File metadata = new File();
        metadata.setName(name);
        metadata.setMimeType(GOOGLE_DOC_MIME);

        if (folderId != null && !folderId.isBlank()) {
            metadata.setParents(List.of(folderId));
        }

        File created = drive.files().create(metadata)
                .setFields("id, name, webViewLink")
                .execute();

        return "Created document: id=%s, name=%s, url=%s"
                .formatted(created.getId(), created.getName(), created.getWebViewLink());
    }

    @Tool(name = "create_folder", description = """
            Create a new folder in Google Drive.
            Returns the ID, name and webViewLink of the created folder.
            """)
    public String createFolder(
            @ToolParam(description = "Name of the new folder", required = true) String name,
            @ToolParam(description = "Parent folder ID where the folder should be created. Omit for My Drive root.", required = false) String parentFolderId
    ) throws IOException {
        File metadata = new File();
        metadata.setName(name);
        metadata.setMimeType(FOLDER_MIME);

        if (parentFolderId != null && !parentFolderId.isBlank()) {
            metadata.setParents(List.of(parentFolderId));
        }

        File created = drive.files().create(metadata)
                .setFields("id, name, webViewLink")
                .execute();

        return "Created folder: id=%s, name=%s, url=%s"
                .formatted(created.getId(), created.getName(), created.getWebViewLink());
    }

    @Tool(name = "create_spreadsheet", description = """
            Create a new empty Google Sheet (spreadsheet) in Google Drive.
            Returns the ID, name and webViewLink of the created spreadsheet.
            """)
    public String createSpreadsheet(
            @ToolParam(description = "Name of the new spreadsheet", required = true) String name,
            @ToolParam(description = "Parent folder ID where the spreadsheet should be created. Omit for My Drive root.", required = false) String folderId
    ) throws IOException {
        File metadata = new File();
        metadata.setName(name);
        metadata.setMimeType(GOOGLE_SHEET_MIME);

        if (folderId != null && !folderId.isBlank()) {
            metadata.setParents(List.of(folderId));
        }

        File created = drive.files().create(metadata)
                .setFields("id, name, webViewLink")
                .execute();

        return "Created spreadsheet: id=%s, name=%s, url=%s"
                .formatted(created.getId(), created.getName(), created.getWebViewLink());
    }

    @Tool(name = "create_presentation", description = """
            Create a new empty Google Slides presentation in Google Drive.
            Returns the ID, name and webViewLink of the created presentation.
            """)
    public String createPresentation(
            @ToolParam(description = "Name of the new presentation", required = true) String name,
            @ToolParam(description = "Parent folder ID where the presentation should be created. Omit for My Drive root.", required = false) String folderId
    ) throws IOException {
        File metadata = new File();
        metadata.setName(name);
        metadata.setMimeType(GOOGLE_SLIDES_MIME);

        if (folderId != null && !folderId.isBlank()) {
            metadata.setParents(List.of(folderId));
        }

        File created = drive.files().create(metadata)
                .setFields("id, name, webViewLink")
                .execute();

        return "Created presentation: id=%s, name=%s, url=%s"
                .formatted(created.getId(), created.getName(), created.getWebViewLink());
    }

    @Tool(name = "update_document", description = """
            Write (replace) the text content of an existing Google Doc.
            The provided plain text will become the full content of the document.
            Returns confirmation with the document ID and name.
            """)
    public String updateDocument(
            @ToolParam(description = "The Google Drive file ID of the Google Doc to update", required = true) String fileId,
            @ToolParam(description = "The new plain text content to write into the document", required = true) String content
    ) throws IOException {
        ByteArrayContent mediaContent = ByteArrayContent.fromString("text/plain", content);
        File updated = drive.files().update(fileId, new File(), mediaContent)
                .setFields("id, name")
                .execute();

        return "Updated document: id=%s, name=%s".formatted(updated.getId(), updated.getName());
    }

    @Tool(name = "update_spreadsheet", description = """
            Write (replace) the content of an existing Google Sheet using CSV data.
            The provided CSV text will become the full content of the spreadsheet.
            Returns confirmation with the spreadsheet ID and name.
            """)
    public String updateSpreadsheet(
            @ToolParam(description = "The Google Drive file ID of the Google Sheet to update", required = true) String fileId,
            @ToolParam(description = "The new CSV content to write into the spreadsheet", required = true) String csvContent
    ) throws IOException {
        ByteArrayContent mediaContent = ByteArrayContent.fromString("text/csv", csvContent);
        File updated = drive.files().update(fileId, new File(), mediaContent)
                .setFields("id, name")
                .execute();

        return "Updated spreadsheet: id=%s, name=%s".formatted(updated.getId(), updated.getName());
    }

    @Tool(name = "update_presentation", description = """
            Write (replace) the text content of an existing Google Slides presentation.
            The provided plain text will become the full content of the presentation.
            Slides are separated by lines containing only "---".
            The first line of each slide becomes the title; remaining lines become the body.
            Returns confirmation with the presentation ID and name.
            """)
    public String updatePresentation(
            @ToolParam(description = "The Google Drive file ID of the Google Slides presentation to update", required = true) String fileId,
            @ToolParam(description = "The new plain text content. Separate slides with lines containing only '---'. First line of each slide is the title, rest is body.", required = true) String content
    ) throws IOException {
        Presentation presentation = slides.presentations().get(fileId).execute();
        List<Request> requests = new ArrayList<>();

        // Delete all existing slides except the first (Slides API requires at least one slide)
        List<Page> existingSlides = presentation.getSlides();
        if (existingSlides != null && existingSlides.size() > 1) {
            for (int i = existingSlides.size() - 1; i >= 1; i--) {
                requests.add(new Request().setDeleteObject(
                        new DeleteObjectRequest().setObjectId(existingSlides.get(i).getObjectId())));
            }
        }

        // Parse content into slides (split on lines that are exactly "---")
        String[] slideBlocks = content.split("(?m)^---$");
        List<String[]> slideTexts = new ArrayList<>();
        for (String block : slideBlocks) {
            String trimmed = block.strip();
            if (!trimmed.isEmpty()) {
                slideTexts.add(trimmed.split("\n", 2));
            }
        }

        if (slideTexts.isEmpty()) {
            return "No content provided. Presentation was not updated.";
        }

        // Update the first existing slide
        String firstSlideId = existingSlides != null && !existingSlides.isEmpty()
                ? existingSlides.get(0).getObjectId() : null;
        List<String> slideIds = new ArrayList<>();
        if (firstSlideId != null) {
            slideIds.add(firstSlideId);
        }

        // Create additional slides for remaining blocks
        for (int i = 1; i < slideTexts.size(); i++) {
            String newSlideId = "slide_" + UUID.randomUUID().toString().replace("-", "");
            requests.add(new Request().setCreateSlide(
                    new CreateSlideRequest()
                            .setObjectId(newSlideId)
                            .setInsertionIndex(i)
                            .setSlideLayoutReference(new LayoutReference().setPredefinedLayout("TITLE_AND_BODY"))));
            slideIds.add(newSlideId);
        }

        // Apply slide creation requests first
        if (!requests.isEmpty()) {
            slides.presentations().batchUpdate(fileId,
                    new BatchUpdatePresentationRequest().setRequests(requests)).execute();
            requests.clear();
        }

        // Re-fetch presentation to get updated slide/shape IDs
        presentation = slides.presentations().get(fileId).execute();
        List<Page> updatedSlides = presentation.getSlides();

        // Populate each slide's title and body
        for (int i = 0; i < Math.min(slideTexts.size(), updatedSlides.size()); i++) {
            Page slide = updatedSlides.get(i);
            String[] parts = slideTexts.get(i);
            String titleText = parts[0].strip();
            String bodyText  = parts.length > 1 ? parts[1].strip() : "";

            for (PageElement element : slide.getPageElements()) {
                if (element.getShape() == null || element.getShape().getPlaceholder() == null) continue;
                String pType = element.getShape().getPlaceholder().getType();
                String shapeId = element.getObjectId();

                String text = switch (pType) {
                    case "TITLE", "CENTERED_TITLE" -> titleText;
                    case "BODY", "SUBTITLE" -> bodyText;
                    default -> null;
                };

                if (text != null) {
                    requests.add(new Request().setDeleteText(
                            new DeleteTextRequest()
                                    .setObjectId(shapeId)
                                    .setTextRange(new Range().setType("ALL"))));
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

        if (!requests.isEmpty()) {
            slides.presentations().batchUpdate(fileId,
                    new BatchUpdatePresentationRequest().setRequests(requests)).execute();
        }

        return "Updated presentation: id=%s, name=%s, slides=%d"
                .formatted(fileId, presentation.getTitle(), slideTexts.size());
    }

    // --- Helpers ---

    private String formatFileList(List<File> files) {
        if (files == null || files.isEmpty()) {
            return "No files found.";
        }
        return files.stream()
                .map(this::formatFile)
                .collect(Collectors.joining("\n---\n"));
    }

    private String formatFile(File f) {
        return """
                id:           %s
                name:         %s
                mimeType:     %s
                size:         %s bytes
                modifiedTime: %s
                webViewLink:  %s
                parents:      %s
                """.formatted(
                f.getId(),
                f.getName(),
                f.getMimeType(),
                f.getSize() != null ? f.getSize() : "N/A",
                f.getModifiedTime() != null ? f.getModifiedTime() : "N/A",
                f.getWebViewLink() != null ? f.getWebViewLink() : "N/A",
                f.getParents() != null ? String.join(", ", f.getParents()) : "N/A"
        );
    }
}
