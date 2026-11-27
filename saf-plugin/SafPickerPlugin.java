package ru.sunnails.suncamera;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.OutputStream;

@CapacitorPlugin(name = "SafPicker")
public class SafPickerPlugin extends Plugin {

    private static final int REQUEST_PICK = 5150;
    private static final String PREFS = "safpicker";
    private static final String KEY_TREE = "treeUri";

    private PluginCall pendingCall;

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Activity activity = bridge.getActivity();
        if (activity == null) {
            call.reject("Нет активности");
            return;
        }
        pendingCall = call;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        activity.startActivityForResult(intent, REQUEST_PICK);
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK) {
            return;
        }
        PluginCall call = pendingCall;
        pendingCall = null;
        if (call == null) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            call.reject("Папка не выбрана");
            return;
        }
        Uri treeUri = data.getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            // право может не сохраниться — работаем без него в рамках сессии
        }
        prefs().edit().putString(KEY_TREE, treeUri.toString()).apply();
        JSObject ret = new JSObject();
        ret.put("uri", treeUri.toString());
        ret.put("name", folderName(treeUri));
        call.resolve(ret);
    }

    @PluginMethod
    public void getFolder(PluginCall call) {
        String tree = prefs().getString(KEY_TREE, null);
        JSObject ret = new JSObject();
        if (tree == null) {
            ret.put("selected", false);
            call.resolve(ret);
        } else {
            Uri treeUri = Uri.parse(tree);
            ret.put("selected", true);
            ret.put("uri", tree);
            ret.put("name", folderName(treeUri));
            call.resolve(ret);
        }
    }

    @PluginMethod
    public void clearFolder(PluginCall call) {
        prefs().edit().remove(KEY_TREE).apply();
        call.resolve();
    }

    @PluginMethod
    public void listNames(PluginCall call) {
        Uri treeUri = currentTree();
        if (treeUri == null) {
            call.reject("Папка не выбрана");
            return;
        }
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            Cursor c = getContext().getContentResolver().query(childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            JSArray arr = new JSArray();
            if (c != null) {
                while (c.moveToNext()) {
                    arr.put(c.getString(0));
                }
                c.close();
            }
            JSObject ret = new JSObject();
            ret.put("names", arr);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Папка недоступна: " + e.getMessage());
        }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        Uri treeUri = currentTree();
        if (treeUri == null) {
            call.reject("Папка не выбрана");
            return;
        }
        String base64 = call.getString("data");
        String name = call.getString("name", "photo.jpg");
        String mime = call.getString("mime", "image/jpeg");
        if (base64 == null) {
            call.reject("Пустые данные");
            return;
        }
        OutputStream os = null;
        try {
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            Uri newUri = DocumentsContract.createDocument(
                    getContext().getContentResolver(), docUri, mime, name);
            if (newUri == null) {
                call.reject("Не удалось создать файл");
                return;
            }
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            os = getContext().getContentResolver().openOutputStream(newUri);
            if (os == null) {
                call.reject("Не удалось открыть файл для записи");
                return;
            }
            os.write(bytes);
            os.flush();
            String realName = queryDisplayName(newUri);
            JSObject ret = new JSObject();
            ret.put("name", realName != null ? realName : name);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Ошибка записи: " + e.getMessage());
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Uri currentTree() {
        String tree = prefs().getString(KEY_TREE, null);
        return tree == null ? null : Uri.parse(tree);
    }

    private String folderName(Uri treeUri) {
        try {
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            String n = queryDisplayName(docUri);
            if (n != null) {
                return n;
            }
        } catch (Exception e) {
        }
        return treeUri.getLastPathSegment();
    }

    private String queryDisplayName(Uri docUri) {
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(docUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception e) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignore) {
                }
            }
        }
        return null;
    }
}
