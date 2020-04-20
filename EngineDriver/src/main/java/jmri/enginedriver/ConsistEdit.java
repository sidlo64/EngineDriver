/*Copyright (C) 2017 M. Steve Todd mstevetodd@gmail.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package jmri.enginedriver;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import jmri.enginedriver.Consist.ConLoco;
import jmri.enginedriver.util.SwipeDetector;

public class ConsistEdit extends Activity implements OnGestureListener {
    public static final int LIGHT_OFF = 0;
    public static final int LIGHT_FOLLOW = 1;
    public static final int LIGHT_UNKNOWN = 2;
    public static final String LIGHT_TEXT_OFF = "Off";
    public static final String LIGHT_TEXT_FOLLOW = "Follow Fn Btn";
    public static final String LIGHT_TEXT_UNKNOWN = "Unknown";

    private String CONSIST_EDIT_LABEL_LEAD = "LEAD";
    private String CONSIST_EDIT_LABEL_TRAIL = "TRAIL";

    static public final int RESULT_CON_EDIT = RESULT_FIRST_USER;

    private threaded_application mainapp;  // hold pointer to mainapp
    private Menu CEMenu;
    private ArrayList<HashMap<String, String>> consistList;
    private SimpleAdapter consistListAdapter;
    private ArrayList<ConLoco> consistObjList;
    private ArrayAdapter<ConLoco> consistObjListAdapter;
    private Spinner consistSpinner;
    private Spinner consistTrailSpinner;
    private Consist consist;
    private int result;                     // set to RESULT_FIRST_USER when something is edited

    private int whichThrottle;
    private char saveConsistsFile = 'Y';

    public ImportExportPreferences importExportPreferences = new ImportExportPreferences();
    SwipeDetector LvSwipeDetector;
    ListView consistLV;

    private GestureDetector myGesture;

    private SharedPreferences prefs;

    public void refreshConsistLists() {
        //clear and rebuild
        consistObjList.clear();
        int pos = 0;
        Collection<ConLoco> cgl = consist.getLocos(); //copy from synchronized map to avoid holding it while iterating
        for (ConLoco l : cgl) {
            if (l.isConfirmed()) {
                consistObjList.add(l);
                if (l.getAddress().equals(consist.getLeadAddr()))
                    consistSpinner.setSelection(pos);
                if (l.getAddress().equals(consist.getTrailAddr()))
                    consistTrailSpinner.setSelection(pos);
                pos++;
            }
        }
        consistObjListAdapter.notifyDataSetChanged();

        consistList.clear();
        for (ConLoco l : cgl) {
            if (l.isConfirmed()) {
                //put values into temp hashmap
                HashMap<String, String> hm = new HashMap<>();
                hm.put("lead_label", consist.getLeadAddr().equals(l.getAddress()) ? CONSIST_EDIT_LABEL_LEAD : "");
                hm.put("trail_label", consist.getTrailAddr().equals(l.getAddress()) ? CONSIST_EDIT_LABEL_TRAIL : "");
                hm.put("loco_addr", l.getAddress());
                hm.put("loco_name", l.toString());
//                hm.put("loco_facing", l.isBackward() ? "Rear" : "Front");
                hm.put("loco_facing", l.isBackward()
                        ? this.getResources().getString(R.string.consistLocoFacingRear)
                        : this.getResources().getString(R.string.consistLocoFacingFront) );

                // the following is ignored if the 'complex' prefConsistFollowRuleStyle is chosen in the preferences
                if (consist.getLeadAddr().equals(l.getAddress())) { // first one is always 'follow'
                    hm.put("loco_light", LIGHT_TEXT_FOLLOW);
                } else {
                    if (l.isLightOn() == LIGHT_OFF) {
                        hm.put("loco_light", LIGHT_TEXT_OFF);
                    } else if (l.isLightOn() == LIGHT_FOLLOW) {
                        hm.put("loco_light", LIGHT_TEXT_FOLLOW);
                    } else {
                        hm.put("loco_light", LIGHT_TEXT_UNKNOWN);
                    }
                }

                consistList.add(hm);
            }
        }
        consistListAdapter.notifyDataSetChanged();
        result = RESULT_CON_EDIT;
    }


    //Handle messages from the communication thread back to this thread (responses from withrottle)
    @SuppressLint("HandlerLeak")
    class ConsistEditHandler extends Handler {

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case message_type.RESPONSE:                       // see if loco added to or removed from any throttle
                    String response_str = msg.obj.toString();
                    if (response_str.length() >= 3) {
                        char com1 = response_str.charAt(0);
                        char com2 = response_str.charAt(2);
                        if (com1 == 'M' && (com2 == '+' || com2 == '-'))
                            refreshConsistLists();
                    }
                    break;
                case message_type.WIT_CON_RETRY:
                    witRetry(msg.obj.toString());
                    break;
                case message_type.WIT_CON_RECONNECT:
                    refreshConsistLists();
                    break;
                case message_type.DISCONNECT:
                case message_type.SHUTDOWN:
                    disconnect();
                    break;
            }
        }
    }

    private void witRetry(String s) {
        Intent in = new Intent().setClass(this, reconnect_status.class);
        in.putExtra("status", s);
        startActivity(in);
        connection_activity.overridePendingTransition(this, R.anim.fade_in, R.anim.fade_out);
    }


//    @Override
//    public boolean onTouchEvent(MotionEvent event) {
//        return myGesture.onTouchEvent(event);
//    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainapp = (threaded_application) getApplication();
        if (mainapp.isForcingFinish()) {     // expedite
            return;
        }

        prefs = getSharedPreferences("jmri.enginedriver_preferences", 0);

        mainapp.applyTheme(this);
        setTitle(getApplicationContext().getResources().getString(R.string.app_name_ConsistEdit)); // needed in case the langauge was changed from the default

        setContentView(R.layout.consist);
        //put pointer to this activity's handler in main app's shared variable
        mainapp.consist_edit_msg_handler = new ConsistEditHandler();
        myGesture = new GestureDetector(this);

        CONSIST_EDIT_LABEL_LEAD = getResources().getString(R.string.ConsistEditLabelLead);
        CONSIST_EDIT_LABEL_TRAIL = getResources().getString(R.string.ConsistEditLabelTrail);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            whichThrottle = mainapp.throttleCharToInt(extras.getChar("whichThrottle"));
            saveConsistsFile = extras.getChar("saveConsistsFile");
        }

        consist = mainapp.consists[whichThrottle];

        //Set up a list adapter to allow adding the list of recent connections to the UI.
        consistList = new ArrayList<>();
        consistListAdapter = new SimpleAdapter(this, consistList, R.layout.consist_item,
                new String[]{"loco_name", "loco_addr", "lead_label", "trail_label", "loco_facing"},
                new int[]{R.id.con_loco_name, R.id.con_loco_addr_hidden,
                        R.id.con_lead_label, R.id.con_trail_label, R.id.con_loco_facing}
                        );
        consistLV = findViewById(R.id.consist_list);
        consistLV.setAdapter(consistListAdapter);
        consistLV.setOnTouchListener(LvSwipeDetector = new SwipeDetector());
        consistLV.setOnItemClickListener(new locoItemClickListener());

        consistObjList = new ArrayList<>();
        consistSpinner = findViewById(R.id.consist_lead);
        consistObjListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, consistObjList);
        consistObjListAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        consistSpinner.setAdapter(consistObjListAdapter);

        consistSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ConLoco l = (ConLoco) parent.getSelectedItem();
                String lAddr = l.getAddress();
                if (!(consist.getLeadAddr().equals(lAddr))) {
                    consist.setLeadAddr(lAddr);
                    refreshConsistLists();
                }
            }

            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        consistTrailSpinner = findViewById(R.id.consist_trail);
        consistTrailSpinner.setAdapter(consistObjListAdapter);
        consistTrailSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ConLoco l = (ConLoco) parent.getSelectedItem();
                String lAddr = l.getAddress();
                if (!(consist.getTrailAddr().equals(lAddr))) {
                    consist.setTrailAddr(lAddr);
                    refreshConsistLists();
                }
            }

            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        //update consist list
        refreshConsistLists();
        result = RESULT_OK;

        if(!mainapp.shownToastConsistEdit) {
            Toast.makeText(getApplicationContext(), getApplicationContext().getResources().getString(R.string.toastConsistEditHelp), Toast.LENGTH_LONG).show();
            mainapp.shownToastConsistEdit = true;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mainapp.isForcingFinish()) {     //expedite
            this.finish();
            return;
        }
        mainapp.setActivityOrientation(this);  //set screen orientation based on prefs
        if (CEMenu != null) {
            mainapp.displayEStop(CEMenu);
        }
        // suppress popup keyboard until EditText is touched
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    /**
     * Called when the activity is finished.
     */
    @Override
    public void onDestroy() {
        Log.d("Engine_Driver", "ConsistEdit.onDestroy() called");
        super.onDestroy();

        if (saveConsistsFile=='Y') {
            importExportPreferences.getRecentConsistsListFromFile();
            int whichEntryIsBeingUpdated = importExportPreferences.addCurrentConistToBeginningOfList(consist);
            importExportPreferences.writeRecentConsistsListToFile(prefs, whichEntryIsBeingUpdated);
        }
        if (mainapp.consist_edit_msg_handler !=null) {
            mainapp.consist_edit_msg_handler.removeCallbacksAndMessages(null);
            mainapp.consist_edit_msg_handler = null;
        } else {
            Log.d("Engine_Driver", "onDestroy: mainapp.consist_edit_msg_handler is null. Unable to removeCallbacksAndMessages");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.consist_edit_menu, menu);
        CEMenu = menu;
        mainapp.displayEStop(menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle all of the possible menu actions.
        //noinspection SwitchStatementWithTooFewBranches
        switch (item.getItemId()) {
            case R.id.EmerStop:
                mainapp.sendEStopMsg();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    //Always go to throttle if back button pressed
    @Override
    public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_BACK) {
            Intent resultIntent = new Intent();
            resultIntent.putExtra("whichThrottle", mainapp.throttleIntToChar(whichThrottle) );  //pass whichThrottle as an extra
            setResult(result, resultIntent);
            this.finish();  //end this activity
            connection_activity.overridePendingTransition(this, R.anim.fade_in, R.anim.fade_out);
            return true;
        }
        return (super.onKeyDown(key, event));
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        return false;
    }

    @Override
    public void onShowPress(MotionEvent e) {
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    private void disconnect() {
        this.finish();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.onAttach(base));
    }

    // onClick for the Locos list items
    public class locoItemClickListener implements AdapterView.OnItemClickListener {

        public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
            if (LvSwipeDetector.swipeDetected()) {
                if (LvSwipeDetector.getAction() == SwipeDetector.Action.LR) {
                    ViewGroup vg = (ViewGroup) v; //convert to viewgroup for clicked row
                    TextView addrv = (TextView) vg.getChildAt(1); // get address text from 2nd box
                    String address = addrv.getText().toString();

                    if (!consist.getLeadAddr().equals(address)) {
                        consist.remove(address);
                        mainapp.sendMsg(mainapp.comm_msg_handler, message_type.RELEASE, address, whichThrottle);   //release the loco
                        refreshConsistLists();
                    }
//                } else {
                }
            } else {  //no swipe
                // When an item is clicked,
                ViewGroup vg = (ViewGroup) v; //convert to viewgroup for clicked row
                TextView addrv = (TextView) vg.getChildAt(1); // get address text from 2nd box
                String address = addrv.getText().toString();

                try {
                    consist.setBackward(address, ! consist.isBackward(address));
                } catch (Exception e) {    // isBackward returns null if address is not in consist - should not happen since address was selected from consist list
                    Log.d("Engine_Driver", "ConsistEdit selected engine " + address + " that is not in consist");
                }
            }

            refreshConsistLists();
        }
    }

}