package com.shaibarack.memer;

import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsProvider;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;

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

    private static final String ROOT = "Memes";
    private static final String MIME_TYPE_IMAGE = "image/*";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        Log.d("XXX", "queryRoots");
        MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        result.newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT)
                .add(Root.COLUMN_TITLE, getContext().getString(R.string.app_name))
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY
                        // | Root.FLAG_SUPPORTS_RECENTS
                        // | Root.FLAG_SUPPORTS_SEARCH
                        )
                .add(Root.COLUMN_DOCUMENT_ID, ROOT)
                .add(Root.COLUMN_MIME_TYPES, MIME_TYPE_IMAGE)
                .add(Root.COLUMN_ICON, R.drawable.ic_launcher);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        Log.d("XXX", "queryDocument " + documentId);
        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(result, documentId);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
            String sortOrder) throws FileNotFoundException {
        Log.d("XXX", "queryChildDocuments " + parentDocumentId);
        AssetManager assets = getContext().getAssets();
        String[] children;
        try {
            children = assets.list(parentDocumentId);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }

        Log.d("XXX", "children: " + Arrays.asList(children));
        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        for (String child : children) {
            includeFile(result, child);
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode,
            CancellationSignal signal) throws FileNotFoundException {
        Log.d("XXX", "openDocument " + documentId);
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
        boolean isDir = isDirectory(docId);
        result.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, docId)
                .add(Document.COLUMN_DISPLAY_NAME, getDisplayName(docId))
                .add(Document.COLUMN_SIZE, null)
                .add(Document.COLUMN_MIME_TYPE, isDir ? Document.MIME_TYPE_DIR : MIME_TYPE_IMAGE)
                .add(Document.COLUMN_LAST_MODIFIED, null)
                .add(Document.COLUMN_FLAGS,
                        isDir ? Document.FLAG_DIR_PREFERS_GRID : Document.FLAG_SUPPORTS_THUMBNAIL);
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
        int lastSeparator = docId.lastIndexOf(File.separator);
        if (lastSeparator == -1) {
            return docId;
        } else {
            // Truncate filename extension
            return docId.substring(lastSeparator, docId.length() - 4);
        }
    }
}
