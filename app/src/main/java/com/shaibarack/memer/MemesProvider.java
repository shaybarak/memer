package com.shaibarack.memer;

import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import static android.provider.DocumentsContract.Document;
import static android.provider.DocumentsContract.Root;

/**
 * Storage Access Framework provider for memes.
 */
public class MemesProvider extends DocumentsProvider {

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
    };

    private static final String ROOT = "root";
    private static final String MIME_TYPE_IMAGE = "image/*";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        MatrixCursor.RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, ROOT);
        row.add(Root.COLUMN_SUMMARY, getContext().getString(R.string.app_name));
        row.add(Root.COLUMN_FLAGS,
                Root.FLAG_LOCAL_ONLY |
                // TODO: expose some recents functionality
                // Root.FLAG_SUPPORTS_RECENTS |
                Root.FLAG_SUPPORTS_SEARCH);
        row.add(Root.COLUMN_TITLE, getContext().getString(R.string.app_name));
        // We're mirroring a filesystem so column IDs are relative paths and the root is ""
        row.add(Root.COLUMN_DOCUMENT_ID, "");
        // The child MIME types are used to filter the roots and only present to the user roots
        // that contain the desired type somewhere in their file hierarchy.
        row.add(Root.COLUMN_MIME_TYPES, MIME_TYPE_IMAGE);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(result, documentId);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
            String sortOrder) throws FileNotFoundException {
        AssetManager assets = getContext().getAssets();
        String[] children;
        try {
            children = assets.list(parentDocumentId);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }

        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        for (String child : children) {
            includeFile(result, child);
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode,
            CancellationSignal signal) throws FileNotFoundException {
        try {
            return getContext().getAssets().openFd(documentId).getParcelFileDescriptor();
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    private String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    private void includeFile(MatrixCursor result, String docId) {
        int flags = Document.FLAG_SUPPORTS_THUMBNAIL;
        if (isDirectory(docId)) {
            // We like grids. Grids have nicer thumbnails.
            flags |= Document.FLAG_DIR_PREFERS_GRID;
        }

        MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, getDisplayName(docId));
        row.add(Document.COLUMN_SIZE, null);
        row.add(Document.COLUMN_MIME_TYPE, MIME_TYPE_IMAGE);
        row.add(Document.COLUMN_LAST_MODIFIED, null);
        row.add(Document.COLUMN_FLAGS, flags);
    }

    private static boolean isDirectory(String docId) {
        // AssetManager doesn't offer an easy way to determine if a path is an asset directory or an
        // asset file. This is in line with the general understanding that you're expected to know
        // what is in your assets.
        // Instead we rely on this hack:
        return !docId.contains(".");
    }

    /** Extracts simple name from docId, e.g. "cats/Grumpy Cat.jpg" is "Grumpy Cat". */
    private static String getDisplayName(String docId) {
        return docId.substring(docId.lastIndexOf(File.separator), docId.length() - 4);
    }
}
