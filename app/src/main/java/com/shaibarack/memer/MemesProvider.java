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
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

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
    private static final String MIME_TYPE_IMAGE = "image/jpeg";
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

        query = query.toLowerCase();
        MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));

        // Iterate through all files in the file structure under the root.
        Queue<String> pending = new LinkedList<>();

        // Start by adding the parent to the list of files to be processed
        pending.add(rootId);

        // Do while we still have unexamined files
        while (!pending.isEmpty()) {
            // Take a file from the list of unprocessed files
            String docId = pending.poll();
            if (isDirectory(docId)) {
                // If it's a directory, add all its children to the unprocessed list
                try {
                    for (String child : mAssets.list(docId)) {
                        pending.add(docId + File.separator + child);
                    }
                } catch (IOException e) {
                    Log.e("MemesProvider",
                            "querySearchDocuments failed while listing children", e);
                }
            } else {
                // If it's a file and it matches, add it to the result cursor.
                if (getFileDisplayName(docId).toLowerCase().contains(query)) {
                    includeFile(result, docId);
                }
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
        boolean isDir = isDirectory(docId);
        result.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, docId)
                .add(Document.COLUMN_DISPLAY_NAME,
                        isDir ? getDirDisplayName(docId) : getFileDisplayName(docId))
                .add(Document.COLUMN_SIZE, null)
                .add(Document.COLUMN_MIME_TYPE, isDir ? Document.MIME_TYPE_DIR : MIME_TYPE_IMAGE)
                .add(Document.COLUMN_LAST_MODIFIED, null)
                .add(Document.COLUMN_FLAGS,
                        isDir ? Document.FLAG_DIR_PREFERS_GRID : Document.FLAG_SUPPORTS_THUMBNAIL);
    }

    private List<String> getRecents() {
        return Arrays.asList(mPrefs.getString(RECENTS_KEY, "").split(","));
    }

    private void addToRecents(String docId) {
        List<String> recents = getRecents();
        if (recents.remove(docId)) {
            // Can add new element to front without checking capacity
            recents.add(0, docId);
        } else if (recents.size() >= MAX_RECENTS) {
            recents.add(0, docId);
            recents = recents.subList(0, MAX_RECENTS);
        }
        String toPref = TextUtils.join(",", recents);
        mPrefs.edit().putString(RECENTS_KEY, toPref).commit();
    }

    private static boolean isDirectory(String docId) {
        // AssetManager doesn't offer an easy way to determine if a path is an asset directory or an
        // asset file. This is in line with the general understanding that you're expected to know
        // what is in your assets.
        // Instead we rely on this hack:
        return !docId.contains(".");
    }

    /**
     * Extracts simple name from directory docId.
     * Example: for "Memes/Cats" returns "Cats".
     */
    private static String getDirDisplayName(String docId) {
        int lastSeparator = Math.max(docId.lastIndexOf(File.separator), 0);
        return docId.substring(lastSeparator + 1);
    }

    /**
     * Extracts simple name from file docId.
     * Example: for "Memes/Cats/Grumpy Cat.jpg" returns "Grumpy Cat".
     */
    private static String getFileDisplayName(String docId) {
        int lastSeparator = Math.max(docId.lastIndexOf(File.separator), 0);
        return docId.substring(lastSeparator + 1, docId.length() - 4);
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
