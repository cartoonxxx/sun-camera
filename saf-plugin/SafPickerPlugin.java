package ru.sunnails.suncamera;

import android.app.Activity;
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
import java.util.ArrayList;

@CapacitorPlugin(name = "SafPicker")
public class SafPickerPlugin extends Plugin {

    private static final int REQ_PICK = 6101;
    private static final String PREFS = "safpicker";
    private PluginCall pendingCall;

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Активность недоступна");
            return;
        }
        pendingCall = call;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, intent, REQ_PICK);
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || pendingCall == null) {
            return;
        }
        PluginCall call = pendingCall;
        pendingCall = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            call.reject("Выбор папки отменён");
            return;
        }
        Uri uri = data.getData();
        Activity activity = getActivity();
        try {
            activity.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .edit().putString("tree", uri.toString()).apply();
        JSObject res = new JSObject();
        res.put("uri", uri.toString());
        call.resolve(res);
    }

    private Uri treeUri() {
        Activity activity = getActivity();
        if (activity == null) {
            return null;
        }
        String s = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString("tree", null);
        return s == null ? null : Uri.parse(s);
    }

    @PluginMethod
    public void getFolder(PluginCall call) {
        Uri u = treeUri();
        JSObject res = new JSObject();
        res.put("uri", u == null ? null : u.toString());
        call.resolve(res);
    }

    @PluginMethod
    public void clearFolder(PluginCall call) {
        Activity activity = getActivity();
        if (activity != null) {
            activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                    .edit().remove("tree").apply();
        }
        call.resolve();
    }

    @PluginMethod
    public void listFiles(PluginCall call) {
        try {
            Uri tree = treeUri();
            if (tree == null) {
                call.reject("Папка не выбрана");
                return;
            }
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            ArrayList<String> names = new ArrayList<>();
            Cursor c = getActivity().getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    names.add(c.getString(0));
                }
                c.close();
            }
            JSArray arr = new JSArray();
            for (String n : names) {
                arr.put(n);
            }
            JSObject res = new JSObject();
            res.put("files", arr);
            call.resolve(res);
        } catch (Exception e) {
            call.reject("Ошибка чтения папки: " + e.getMessage());
        }
    }

    @PluginMethod
    public void saveFile(PluginCall call) {
        try {
            Uri tree = treeUri();
            if (tree == null) {
                call.reject("Папка не выбрана");
                return;
            }
            String name = call.getString("name");
            String data = call.getString("base64");
            if (name == null || data == null) {
                call.reject("Нет данных для сохранения");
                return;
            }
            int comma = data.indexOf(',');
            if (comma >= 0) {
                data = data.substring(comma + 1);
            }
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            Uri newUri = DocumentsContract.createDocument(
                    getActivity().getContentResolver(), parent, "image/jpeg", name);
            if (newUri == null) {
                call.reject("Не удалось создать файл");
                return;
            }
            OutputStream os = getActivity().getContentResolver().openOutputStream(newUri, "w");
            os.write(bytes);
            os.flush();
            os.close();
            JSObject res = new JSObject();
            res.put("uri", newUri.toString());
            call.resolve(res);
        } catch (Exception e) {
            call.reject("Ошибка сохранения: " + e.getMessage());
        }
    }
}

