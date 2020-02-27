package com.blackboxembedded.WunderLINQ;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;

public class ContactListActivity extends AppCompatActivity {

    public final static String TAG = "ContactList";

    private ListView contactList;

    private SharedPreferences sharedPrefs;

    private ArrayList<String> contacts;
    private ArrayList<String> phoneNumbers;
    private ArrayList<Drawable> photoId;

    private int lastPosition = 0;

    private static final int PERMISSION_REQUEST_READ_CONTACTS = 102;

    private static final String[] PROJECTION = new String[] {
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
            ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
    };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        AppUtils.adjustDisplayScale(this, getResources().getConfiguration());
        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_contact_list);

        contactList = findViewById(R.id.lv_contacts);
        contactList.setOnTouchListener(new OnSwipeTouchListener(this) {
            @Override
            public void onSwipeRight() {
                goBack();
            }
        });

        String orientation = sharedPrefs.getString("prefOrientation", "0");
        if (!orientation.equals("0")){
            if(orientation.equals("1")){
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            } else if (orientation.equals("2")){
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
        }

        showActionBar();

        // Check Read Contacts permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (this.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(getString(R.string.contacts_alert_title));
                builder.setMessage(getString(R.string.contacts_alert_body));
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @TargetApi(23)
                    public void onDismiss(DialogInterface dialog) {
                        requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, PERMISSION_REQUEST_READ_CONTACTS);
                    }
                });
                builder.show();
            } else {
                // Android version is lesser than 6.0 or the permission is already granted.
                updateList();
            }
        }  else {
            // Android version is lesser than 6.0 or the permission is already granted.
            updateList();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_READ_CONTACTS) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission is granted
                updateList();
            } else {
                //GO Back
                goBack();
            }
        }
    }

    public void updateList(){
        // Check the SDK version and whether the permission is already granted or not.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, PERMISSION_REQUEST_READ_CONTACTS);
            //After this point you wait for callback in onRequestPermissionsResult(int, String[], int[]) overriden method
        } else {
            // Android version is lesser than 6.0 or the permission is already granted.

            String sortOrder = ContactsContract.Contacts.DISPLAY_NAME + " COLLATE LOCALIZED ASC";
            ContentResolver cr = getContentResolver();
            Cursor cursor;
            if (sharedPrefs.getString("prefContactsFilter","1").contains("1")){
                cursor = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI.buildUpon()
                        .appendQueryParameter(ContactsContract.REMOVE_DUPLICATE_ENTRIES, "1")
                        .build(), PROJECTION, "starred=?", new String[] {"1"}, sortOrder);
            } else {
                cursor = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI.buildUpon()
                        .appendQueryParameter(ContactsContract.REMOVE_DUPLICATE_ENTRIES, "1")
                        .build(), PROJECTION, null, null, sortOrder);
            }

            if (cursor != null) {
                try {
                    HashSet<String> normalizedNumbersAlreadyFound = new HashSet<>();
                    contacts = new ArrayList<>();
                    phoneNumbers = new ArrayList<>();
                    photoId = new ArrayList<>();
                    final int contactIdIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID);
                    final int displayNameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
                    final int phoneTypeIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE);
                    final int phoneNumIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                    final int photoURIIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI);
                    final int lastNameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME);
                    final int givenNameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME);
                    final int normalizedNumIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER);

                    long contactId;
                    String displayName, phoneType, phoneNumber,photoURI, lastName, givenName, normalNum;
                    while (cursor.moveToNext()) {
                        contactId = cursor.getLong(contactIdIndex);
                        displayName = cursor.getString(displayNameIndex);
                        phoneType = cursor.getString(phoneTypeIndex);
                        phoneNumber = cursor.getString(phoneNumIndex);
                        photoURI = cursor.getString(photoURIIndex);
                        lastName = cursor.getString(lastNameIndex);
                        givenName = cursor.getString(givenNameIndex);
                        normalNum = cursor.getString(normalizedNumIndex);

                        if (phoneType != null && normalNum != null) {
                            if((phoneType.equals("0")) || (phoneType.equals("1")) || phoneType.equals("2") || phoneType.equals("3")) {
                                if (normalizedNumbersAlreadyFound.add(normalNum.replaceAll("\\p{C}", ""))) {
                                    Log.d(TAG, "Adding Contact");
                                    contacts.add(displayName + " (" + typeIDtoString(Integer.parseInt(phoneType)) + ")");
                                    phoneNumbers.add(normalNum);
                                    Drawable photo;
                                    photo = getResources().getDrawable(R.drawable.ic_default_contact, getTheme());

                                    if (photoURI != null) {

                                        try {
                                            Bitmap photoBitmap = MediaStore.Images.Media
                                                    .getBitmap(getContentResolver(),
                                                            Uri.parse(photoURI));
                                            photo = new BitmapDrawable(getResources(), photoBitmap);
                                        } catch (FileNotFoundException e) {
                                            e.printStackTrace();
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                    photoId.add(photo);
                                }
                            } else {
                                Log.d(TAG, "Not Adding Contact");
                            }
                        }
                    }
                } finally {
                    cursor.close();
                }
            }

            ContactListView adapter = new
                    ContactListView(this, contacts, photoId);
            contactList = findViewById(R.id.lv_contacts);
            contactList.setAdapter(adapter);
            contactList.setOnItemClickListener(new AdapterView.OnItemClickListener() {

                @Override
                public void onItemClick(AdapterView<?> parent, final View view,
                                        int position, long id) {
                    lastPosition = position;
                    // Call Number
                    boolean callPerms = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (getApplication().checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                            final AlertDialog.Builder builder = new AlertDialog.Builder(ContactListActivity.this);
                            builder.setTitle(getString(R.string.call_alert_title));
                            builder.setMessage(getString(R.string.call_alert_body));
                            builder.setPositiveButton(android.R.string.ok, null);
                            builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                                @TargetApi(23)
                                public void onDismiss(DialogInterface dialog) {
                                    requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, PERMISSION_REQUEST_READ_CONTACTS);
                                }
                            });
                            builder.show();
                        } else {
                            // Android version is lesser than 6.0 or the permission is already granted.
                            callPerms = true;
                        }
                    }  else {
                        // Android version is lesser than 6.0 or the permission is already granted.
                        callPerms = true;
                    }
                    if (callPerms) {
                        Intent callHomeIntent = new Intent(Intent.ACTION_CALL);
                        callHomeIntent.setData(Uri.parse("tel:" + phoneNumbers.get(position)));
                        startActivity(callHomeIntent);
                    }
                }

            });
        }
    }
    String typeIDtoString (int typeID){
        String type = "";
        switch (typeID){
            case 0:
                type = getResources().getString(R.string.contact_type_0);
                break;
            case 1:
                type = getResources().getString(R.string.contact_type_1);
                break;
            case 2:
                type = getResources().getString(R.string.contact_type_2);
                break;
            case 3:
                type = getResources().getString(R.string.contact_type_3);
                break;
            case 4:
                type = getResources().getString(R.string.contact_type_4);
                break;
            case 5:
                type = getResources().getString(R.string.contact_type_5);
                break;
            case 6:
                type = getResources().getString(R.string.contact_type_6);
                break;
            case 7:
                type = getResources().getString(R.string.contact_type_7);
                break;
            case 8:
                type = getResources().getString(R.string.contact_type_8);
                break;
            case 9:
                type = getResources().getString(R.string.contact_type_9);
                break;
            case 10:
                type = getResources().getString(R.string.contact_type_10);
                break;
            case 11:
                type = getResources().getString(R.string.contact_type_11);
                break;
            case 12:
                type = getResources().getString(R.string.contact_type_12);
                break;
            case 13:
                type = getResources().getString(R.string.contact_type_13);
                break;
            case 14:
                type = getResources().getString(R.string.contact_type_14);
                break;
            case 15:
                type = getResources().getString(R.string.contact_type_15);
                break;
            case 16:
                type = getResources().getString(R.string.contact_type_16);
                break;
            case 17:
                type = getResources().getString(R.string.contact_type_17);
                break;
            case 18:
                type = getResources().getString(R.string.contact_type_18);
                break;
            case 19:
                type = getResources().getString(R.string.contact_type_19);
                break;
            case 20:
                type = getResources().getString(R.string.contact_type_20);
                break;
        }
        return type;
    }

    private void showActionBar(){
        LayoutInflater inflator = (LayoutInflater) this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflator.inflate(R.layout.actionbar_nav, null);
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(false);
        actionBar.setDisplayShowHomeEnabled(false);
        actionBar.setDisplayShowCustomEnabled(true);
        actionBar.setDisplayShowTitleEnabled(false);
        actionBar.setCustomView(v);

        TextView navbarTitle = findViewById(R.id.action_title);
        navbarTitle.setText(R.string.contactlist_title);

        ImageButton backButton = findViewById(R.id.action_back);
        backButton.setOnClickListener(mClickListener);

        ImageButton forwardButton = findViewById(R.id.action_forward);
        forwardButton.setVisibility(View.INVISIBLE);
    }

    private void goBack(){
        Intent backIntent = new Intent(ContactListActivity.this, com.blackboxembedded.WunderLINQ.TaskList.TaskActivity.class);
        startActivity(backIntent);
    }

    private View.OnClickListener mClickListener = new View.OnClickListener() {

        @Override
        public void onClick(View v) {
            switch(v.getId()) {
                case R.id.action_back:
                    // Go back
                    goBack();
                    break;
            }
        }
    };

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Log.d(TAG, "Keycode: " + keyCode);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                goBack();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if ((contactList.getSelectedItemPosition() == (contacts.size() - 1)) && lastPosition == (contacts.size() - 1) ){
                    contactList.setSelection(0);
                }
                lastPosition = contactList.getSelectedItemPosition();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (contactList.getSelectedItemPosition() == 0 && lastPosition == 0){
                    contactList.setSelection(contacts.size() - 1);
                }
                lastPosition = contactList.getSelectedItemPosition();
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }
}
