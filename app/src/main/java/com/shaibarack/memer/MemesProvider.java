package com.shaibarack.memer;

import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.DocumentsProvider;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static android.provider.DocumentsContract.Document;
import static android.provider.DocumentsContract.Root;

/**
 * Storage Access Framework provider for memes.
 */
public class MemesProvider extends DocumentsProvider {

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_ICON,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
    };

    private static final String ROOT = "Memes";
    private static final String MIME_TYPE_IMAGE = "image/*";
    private static final String RECENTS_KEY = "recents";
    private static final int MAX_RECENTS = 64;

    AssetManager mAssets;
    SharedPreferences mPrefs;

    @Override
    public boolean onCreate() {
        mAssets = getContext().getAssets();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        Log.d("MemesProvider", "queryRoots");
        MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        result.newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT)
                .add(Root.COLUMN_TITLE, getContext().getString(R.string.app_name))
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY
                        | Root.FLAG_SUPPORTS_RECENTS
                        | Root.FLAG_SUPPORTS_SEARCH)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT)
                .add(Root.COLUMN_MIME_TYPES, MIME_TYPE_IMAGE)
                .add(Root.COLUMN_ICON, R.drawable.ic_launcher);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        Log.d("MemesProvider", "queryDocument " + documentId);
        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(result, documentId);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
            String sortOrder) throws FileNotFoundException {
        Log.d("MemesProvider", "queryChildDocuments " + parentDocumentId);
        String[] children;
        try {
            children = mAssets.list(parentDocumentId);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }

        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        for (String child : children) {
            includeFile(result, parentDocumentId + File.separator + child);
        }
        return result;
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint,
            CancellationSignal signal) throws FileNotFoundException {
        try {
            return getContext().getAssets().openFd(documentId);
        } catch (IOException e) {
            Log.e("MemesProvider", "Exception in openDocumentThumbnail for " + documentId, e);
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode,
            CancellationSignal signal) throws FileNotFoundException {
        Log.d("MemesProvider", "openDocument " + documentId);

        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            new TransferThread(mAssets.open(documentId),
                    new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])).start();
            return pipe[0];
        } catch (IOException e) {
            Log.e("MemesProvider", "Exception in openDocument for " + documentId, e);
            throw new FileNotFoundException(e.getMessage());
        } finally {
            addToRecents(documentId);
            setLastModified(documentId, System.currentTimeMillis());
        }
    }

    /**
     * Searches for matching memes.
     * A meme matches if its filename contains the search query.
     * TODO: ranking.
     */
    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection)
            throws FileNotFoundException {
        Log.d("MemesProvider", "querySearchDocuments " + rootId + ", " + query);

        String[] children;
        try {
            children = mAssets.list(rootId);
        } catch (IOException e) {
            Log.e("MemesProvider", "Failed to list children of " + rootId, e);
            throw new FileNotFoundException(e.getMessage());
        }

        query = query.toLowerCase();
        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        for (String child : children) {
            if (child.toLowerCase().contains(query)) {
                includeFile(result, rootId + File.separator + child);
            }
        }
        return result;
    }

    @Override
    public Cursor queryRecentDocuments(String rootId, String[] projection)
            throws FileNotFoundException {
        Log.d("MemesProvider", "queryRecentDocuments");

        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        for (String recent : getRecents()) {
            includeFile(result, recent);
        }
        return result;
    }

    private String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    private void includeFile(MatrixCursor result, String docId) {
        result.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, docId)
                .add(Document.COLUMN_DISPLAY_NAME, docId.equals(ROOT)
                        ? ROOT
                        : getFileDisplayName(docId))
                .add(Document.COLUMN_SIZE, null)
                .add(Document.COLUMN_MIME_TYPE, MIME_TYPE_IMAGE)
                .add(Document.COLUMN_LAST_MODIFIED, getLastModified(docId))
                .add(Document.COLUMN_FLAGS, docId.equals(ROOT)
                        ? Document.FLAG_DIR_PREFERS_GRID
                        : Document.FLAG_SUPPORTS_THUMBNAIL);
    }

    private long getLastModified(String docId) {
        return mPrefs.getLong(docId, 0);
    }

    private void setLastModified(String docId, long currentTimeMillis) {
        mPrefs.edit().putLong(docId, currentTimeMillis).commit();
    }

    private List<String> getRecents() {
        String recents = mPrefs.getString(RECENTS_KEY, "");
        if (recents.isEmpty()) {
            return Collections.EMPTY_LIST;
        } else {
            return Arrays.asList(mPrefs.getString(RECENTS_KEY, "").split(","));
        }
    }

    private void addToRecents(String docId) {
        List<String> oldRecents = getRecents();
        List<String> newRecents = new ArrayList<>(MAX_RECENTS);

        // Add to front
        newRecents.add(docId);
        int recentsCount = 1;
        for (String oldRecent : oldRecents) {
            // Don't add previous instance if encountered
            if (!oldRecent.equals(docId)) {
                newRecents.add(oldRecent);
                recentsCount++;
            }
            // Don't overflow
            if (recentsCount == MAX_RECENTS) {
                break;
            }
        }

        String toPref = TextUtils.join(",", newRecents);
        mPrefs.edit().putString(RECENTS_KEY, toPref).commit();
        Log.w("XXX", "Wrote recents: " + toPref);
    }

    /**
     * Extracts simple name from file docId.
     * Example: for "Memes/Cats/Grumpy Cat.jpg" returns "Grumpy Cat".
     */
    private static String getFileDisplayName(String docId) {
        int lastSeparator = Math.max(docId.lastIndexOf(File.separator), 0);
        return docId.substring(lastSeparator + 1, docId.indexOf("."));
    }

    private static class TransferThread extends Thread {
        private InputStream mIn;
        private OutputStream mOut;

        TransferThread(InputStream in, OutputStream out) {
            mIn = in;
            mOut = out;
        }

        @Override
        public void run() {
            byte[] buf = new byte[1024];
            int len;

            try {
                while ((len = mIn.read(buf)) >= 0) {
                    mOut.write(buf, 0, len);
                }

                mIn.close();
                mOut.flush();
                mOut.close();
            } catch (IOException e) {
                Log.e("MemesProvider", "Exception while transferring file", e);
            }
        }
    }
}
