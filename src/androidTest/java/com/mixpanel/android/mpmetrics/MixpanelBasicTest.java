package com.mixpanel.android.mpmetrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.test.AndroidTestCase;
import android.test.mock.MockContext;
import android.test.mock.MockPackageManager;

import com.mixpanel.android.BuildConfig;
import com.mixpanel.android.util.Base64Coder;
import com.mixpanel.android.util.HttpService;
import com.mixpanel.android.util.RemoteService;
import com.mixpanel.android.viewcrawler.UpdatesFromMixpanel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

public class MixpanelBasicTest extends AndroidTestCase {

    @Override
    protected void setUp() throws Exception {
        mMockPreferences = new TestUtils.EmptyPreferences(getContext());
        AnalyticsMessages messages = AnalyticsMessages.getInstance(getContext());
        messages.hardKill();
        Thread.sleep(2000);

        try {
            SystemInformation systemInformation = SystemInformation.getInstance(mContext);

            final StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("&properties=");
            JSONObject properties = new JSONObject();
            properties.putOpt("$android_lib_version", MPConfig.VERSION);
            properties.putOpt("$android_app_version", systemInformation.getAppVersionName());
            properties.putOpt("$android_version", Build.VERSION.RELEASE);
            properties.putOpt("$android_app_release", systemInformation.getAppVersionCode());
            properties.putOpt("$android_device_model", Build.MODEL);
            queryBuilder.append(URLEncoder.encode(properties.toString(), "utf-8"));
            mAppProperties = queryBuilder.toString();
        } catch (Exception e) {}
    } // end of setUp() method definition

    public void testVersionsMatch() {
        assertEquals(BuildConfig.MIXPANEL_VERSION, MPConfig.VERSION);
    }

    public void testGeneratedDistinctId() {
        String fakeToken = UUID.randomUUID().toString();
        MixpanelAPI mixpanel = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, fakeToken);
        String generatedId1 = mixpanel.getDistinctId();
        assertTrue(generatedId1 != null);

        mixpanel.reset();
        String generatedId2 = mixpanel.getDistinctId();
        assertTrue(generatedId2 != null);
        assertTrue(generatedId1 != generatedId2);
    }

    public void testDeleteDB() {
        Map<String, String> beforeMap = new HashMap<String, String>();
        beforeMap.put("added", "before");
        JSONObject before = new JSONObject(beforeMap);

        Map<String, String> afterMap = new HashMap<String,String>();
        afterMap.put("added", "after");
        JSONObject after = new JSONObject(afterMap);

        MPDbAdapter adapter = new MPDbAdapter(getContext(), "DeleteTestDB");
        adapter.addJSON(before, "ATOKEN", MPDbAdapter.Table.EVENTS, false);
        adapter.addJSON(before, "ATOKEN", MPDbAdapter.Table.PEOPLE, false);
        adapter.addJSON(before, "ATOKEN", MPDbAdapter.Table.GROUPS, false);
        adapter.deleteDB();

        String[] emptyEventsData = adapter.generateDataString(MPDbAdapter.Table.EVENTS, "ATOKEN", true);
        assertEquals(emptyEventsData, null);
        String[] emptyPeopleData = adapter.generateDataString(MPDbAdapter.Table.PEOPLE, "ATOKEN", true);
        assertEquals(emptyPeopleData, null);
        String[] emptyGroupsData = adapter.generateDataString(MPDbAdapter.Table.GROUPS, "ATOKEN", true);
        assertEquals(emptyPeopleData, null);

        adapter.addJSON(after, "ATOKEN", MPDbAdapter.Table.EVENTS, false);
        adapter.addJSON(after, "ATOKEN", MPDbAdapter.Table.PEOPLE, false);
        adapter.addJSON(after, "ATOKEN", MPDbAdapter.Table.GROUPS, false);

        try {
            String[] someEventsData = adapter.generateDataString(MPDbAdapter.Table.EVENTS, "ATOKEN", true);
            JSONArray someEvents = new JSONArray(someEventsData[1]);
            assertEquals(someEvents.length(), 1);
            assertEquals(someEvents.getJSONObject(0).get("added"), "after");

            String[] somePeopleData = adapter.generateDataString(MPDbAdapter.Table.PEOPLE, "ATOKEN", true);
            JSONArray somePeople = new JSONArray(somePeopleData[1]);
            assertEquals(somePeople.length(), 1);
            assertEquals(somePeople.getJSONObject(0).get("added"), "after");

            String[] someGroupsData = adapter.generateDataString(MPDbAdapter.Table.GROUPS, "ATOKEN", true);
            JSONArray someGroups = new JSONArray(somePeopleData[1]);
            assertEquals(someGroups.length(), 1);
            assertEquals(someGroups.getJSONObject(0).get("added"), "after");        } catch (JSONException e) {
            fail("Unexpected JSON or lack thereof in MPDbAdapter test");
        }
    }

    public void testLooperDestruction() {

        final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<JSONObject>();

        final MPDbAdapter explodingDb = new MPDbAdapter(getContext()) {
            @Override
            public int addJSON(JSONObject message, String token, MPDbAdapter.Table table, boolean isAutomatic) {
                if (!isAutomatic) {
                    messages.add(message);
                    throw new RuntimeException("BANG!");
                }

                return 0;
            }
        };

        final AnalyticsMessages explodingMessages = new AnalyticsMessages(getContext()) {
            // This will throw inside of our worker thread.
            @Override
            public MPDbAdapter makeDbAdapter(Context context) {
                return explodingDb;
            }
        };
        MixpanelAPI mixpanel = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, "TEST TOKEN testLooperDisaster") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return explodingMessages;
            }
        };

        try {
            mixpanel.reset();
            assertFalse(explodingMessages.isDead());

            mixpanel.track("event1", null);
            JSONObject found = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(found);
            Thread.sleep(1000);
            assertTrue(explodingMessages.isDead());

            mixpanel.track("event2", null);
            JSONObject shouldntFind = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertNull(shouldntFind);
            assertTrue(explodingMessages.isDead());
        } catch (InterruptedException e) {
            fail("Unexpected interruption");
        }
    }

    public void testEventOperations() throws JSONException {
        final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<JSONObject>();

        final MPDbAdapter eventOperationsAdapter = new MPDbAdapter(getContext()) {
            @Override
            public int addJSON(JSONObject message, String token, MPDbAdapter.Table table, boolean isAutomatic) {
                if (!isAutomatic) {
                    messages.add(message);
                }

                return 1;
            }
        };

        final AnalyticsMessages eventOperationsMessages = new AnalyticsMessages(getContext()) {
            // This will throw inside of our worker thread.
            @Override
            public MPDbAdapter makeDbAdapter(Context context) {
                return eventOperationsAdapter;
            }
        };

        MixpanelAPI mixpanel = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, "Test event operations") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return eventOperationsMessages;
            }
        };

        JSONObject jsonObj1 = new JSONObject();
        JSONObject jsonObj2 = new JSONObject();
        JSONObject jsonObj3 = new JSONObject();
        JSONObject jsonObj4 = new JSONObject();
        Map<String, Object> mapObj1 = new HashMap<>();
        Map<String, Object> mapObj2 = new HashMap<>();
        Map<String, Object> mapObj3 = new HashMap<>();
        Map<String, Object> mapObj4 = new HashMap<>();

        jsonObj1.put("TRACK JSON STRING", "TRACK JSON STRING VALUE");
        jsonObj2.put("TRACK JSON INT", 1);
        jsonObj3.put("TRACK JSON STRING ONCE", "TRACK JSON STRING ONCE VALUE");
        jsonObj4.put("TRACK JSON STRING ONCE", "SHOULD NOT SEE ME");

        mapObj1.put("TRACK MAP STRING", "TRACK MAP STRING VALUE");
        mapObj2.put("TRACK MAP INT", 1);
        mapObj3.put("TRACK MAP STRING ONCE", "TRACK MAP STRING ONCE VALUE");
        mapObj4.put("TRACK MAP STRING ONCE", "SHOULD NOT SEE ME");

        try {
            JSONObject message;
            JSONObject properties;

            mixpanel.track("event1", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event1", message.getString("event"));

            mixpanel.track("event2", jsonObj1);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event2", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertEquals(jsonObj1.getString("TRACK JSON STRING"), properties.getString("TRACK JSON STRING"));

            mixpanel.trackMap("event3", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event3", message.getString("event"));

            mixpanel.trackMap("event4", mapObj1);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event4", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertEquals(mapObj1.get("TRACK MAP STRING"), properties.getString("TRACK MAP STRING"));

            mixpanel.registerSuperProperties(jsonObj2);
            mixpanel.registerSuperPropertiesOnce(jsonObj3);
            mixpanel.registerSuperPropertiesOnce(jsonObj4);
            mixpanel.registerSuperPropertiesMap(mapObj2);
            mixpanel.registerSuperPropertiesOnceMap(mapObj3);
            mixpanel.registerSuperPropertiesOnceMap(mapObj4);

            mixpanel.track("event5", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event5", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertEquals(jsonObj2.getInt("TRACK JSON INT"), properties.getInt("TRACK JSON INT"));
            assertEquals(jsonObj3.getString("TRACK JSON STRING ONCE"), properties.getString("TRACK JSON STRING ONCE"));
            assertEquals(mapObj2.get("TRACK MAP INT"), properties.getInt("TRACK MAP INT"));
            assertEquals(mapObj3.get("TRACK MAP STRING ONCE"), properties.getString("TRACK MAP STRING ONCE"));

            mixpanel.unregisterSuperProperty("TRACK JSON INT");
            mixpanel.track("event6", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event6", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertFalse(properties.has("TRACK JSON INT"));

            mixpanel.clearSuperProperties();
            mixpanel.track("event7", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event7", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertFalse(properties.has("TRACK JSON STRING ONCE"));
        } catch (InterruptedException e) {
            fail("Unexpected interruption");
        }
    }

    public void testPeopleOperations() throws JSONException {
        final List<AnalyticsMessages.PeopleDescription> messages = new ArrayList<AnalyticsMessages.PeopleDescription>();

        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            public void peopleMessage(PeopleDescription heard) {
                messages.add(heard);
            }
        };

        MixpanelAPI mixpanel = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, "TEST TOKEN testIdentifyAfterSet") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        };

        Map<String, Object> mapObj1 = new HashMap<>();
        mapObj1.put("SET MAP INT", 1);
        Map<String, Object> mapObj2 = new HashMap<>();
        mapObj2.put("SET ONCE MAP STR", "SET ONCE MAP VALUE");

        mixpanel.getPeople().identify("TEST IDENTITY");

        mixpanel.getPeople().set("SET NAME", "SET VALUE");
        mixpanel.getPeople().setMap(mapObj1);
        mixpanel.getPeople().increment("INCREMENT NAME", 1);
        mixpanel.getPeople().append("APPEND NAME", "APPEND VALUE");
        mixpanel.getPeople().setOnce("SET ONCE NAME", "SET ONCE VALUE");
        mixpanel.getPeople().setOnceMap(mapObj2);
        mixpanel.getPeople().union("UNION NAME", new JSONArray("[100]"));
        mixpanel.getPeople().unset("UNSET NAME");
        mixpanel.getPeople().trackCharge(100, new JSONObject("{\"name\": \"val\"}"));
        mixpanel.getPeople().clearCharges();
        mixpanel.getPeople().deleteUser();

        JSONObject setMessage = messages.get(0).getMessage().getJSONObject("$set");
        assertEquals("SET VALUE", setMessage.getString("SET NAME"));

        JSONObject setMapMessage = messages.get(1).getMessage().getJSONObject("$set");
        assertEquals(mapObj1.get("SET MAP INT"), setMapMessage.getInt("SET MAP INT"));

        JSONObject addMessage = messages.get(2).getMessage().getJSONObject("$add");
        assertEquals(1, addMessage.getInt("INCREMENT NAME"));

        JSONObject appendMessage = messages.get(3).getMessage().getJSONObject("$append");
        assertEquals("APPEND VALUE", appendMessage.get("APPEND NAME"));

        JSONObject setOnceMessage = messages.get(4).getMessage().getJSONObject("$set_once");
        assertEquals("SET ONCE VALUE", setOnceMessage.getString("SET ONCE NAME"));

        JSONObject setOnceMapMessage = messages.get(5).getMessage().getJSONObject("$set_once");
        assertEquals(mapObj2.get("SET ONCE MAP STR"), setOnceMapMessage.getString("SET ONCE MAP STR"));

        JSONObject unionMessage = messages.get(6).getMessage().getJSONObject("$union");
        JSONArray unionValues = unionMessage.getJSONArray("UNION NAME");
        assertEquals(1, unionValues.length());
        assertEquals(100, unionValues.getInt(0));

        JSONArray unsetMessage = messages.get(7).getMessage().getJSONArray("$unset");
        assertEquals(1, unsetMessage.length());
        assertEquals("UNSET NAME", unsetMessage.get(0));

        JSONObject trackChargeMessage = messages.get(8).getMessage().getJSONObject("$append");
        JSONObject transaction = trackChargeMessage.getJSONObject("$transactions");
        assertEquals(100.0d, transaction.getDouble("$amount"));

        JSONArray clearChargesMessage = messages.get(9).getMessage().getJSONArray("$unset");
        assertEquals(1, clearChargesMessage.length());
        assertEquals("$transactions", clearChargesMessage.getString(0));

        assertTrue(messages.get(10).getMessage().has("$delete"));
    }

    public void testGroupOperations() throws JSONException {
        final List<AnalyticsMessages.GroupDescription> messages = new ArrayList<AnalyticsMessages.GroupDescription>();

        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            public void groupMessage(GroupDescription heard) {
                messages.add(heard);
            }
        };

        MixpanelAPI mixpanel = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, "TEST TOKEN testGroupOperations") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        };

        Map<String, Object> mapObj1 = new HashMap<>();
        mapObj1.put("SET MAP INT", 1);
        Map<String, Object> mapObj2 = new HashMap<>();
        mapObj2.put("SET ONCE MAP STR", "SET ONCE MAP VALUE");

        String groupKey = "group key";
        String groupID = "group id";

        mixpanel.getGroup(groupKey, groupID).set("SET NAME", "SET VALUE");
        mixpanel.getGroup(groupKey, groupID).setMap(mapObj1);
        mixpanel.getGroup(groupKey, groupID).setOnce("SET ONCE NAME", "SET ONCE VALUE");
        mixpanel.getGroup(groupKey, groupID).setOnceMap(mapObj2);
        mixpanel.getGroup(groupKey, groupID).union("UNION NAME", new JSONArray("[100]"));
        mixpanel.getGroup(groupKey, groupID).unset("UNSET NAME");
        mixpanel.getGroup(groupKey, groupID).deleteGroup();

        JSONObject setMessage = messages.get(0).getMessage();
        assertEquals(setMessage.getString("$group_key"), groupKey);
        assertEquals(setMessage.getString("$group_id"), groupID);
        assertEquals("SET VALUE",
                setMessage.getJSONObject("$set").getString("SET NAME"));

        JSONObject setMapMessage = messages.get(1).getMessage();
        assertEquals(setMapMessage.getString("$group_key"), groupKey);
        assertEquals(setMapMessage.getString("$group_id"), groupID);
        assertEquals(mapObj1.get("SET MAP INT"),
                setMapMessage.getJSONObject("$set").getInt("SET MAP INT"));

        JSONObject setOnceMessage = messages.get(2).getMessage();
        assertEquals(setOnceMessage.getString("$group_key"), groupKey);
        assertEquals(setOnceMessage.getString("$group_id"), groupID);
        assertEquals("SET ONCE VALUE",
                setOnceMessage.getJSONObject("$set_once").getString("SET ONCE NAME"));

        JSONObject setOnceMapMessage = messages.get(3).getMessage();
        assertEquals(setOnceMapMessage.getString("$group_key"), groupKey);
        assertEquals(setOnceMapMessage.getString("$group_id"), groupID);
        assertEquals(mapObj2.get("SET ONCE MAP STR"),
                setOnceMapMessage.getJSONObject("$set_once").getString("SET ONCE MAP STR"));

        JSONObject unionMessage = messages.get(4).getMessage();
        assertEquals(unionMessage.getString("$group_key"), groupKey);
        assertEquals(unionMessage.getString("$group_id"), groupID);
        JSONArray unionValues = unionMessage.getJSONObject("$union").getJSONArray("UNION NAME");
        assertEquals(1, unionValues.length());
        assertEquals(100, unionValues.getInt(0));

        JSONObject unsetMessage = messages.get(5).getMessage();
        assertEquals(unsetMessage.getString("$group_key"), groupKey);
        assertEquals(unsetMessage.getString("$group_id"), groupID);
        JSONArray unsetValues = unsetMessage.getJSONArray("$unset");
        assertEquals(1, unsetValues.length());
        assertEquals("UNSET NAME", unsetValues.get(0));

        JSONObject deleteMessage = messages.get(6).getMessage();
        assertEquals(deleteMessage.getString("$group_key"), groupKey);
        assertEquals(deleteMessage.getString("$group_id"), groupID);
        assertTrue(deleteMessage.has("$delete"));
    }

    public void testIdentifyAfterSet() throws InterruptedException, JSONException {
        String token = "TEST TOKEN testIdentifyAfterSet";
        final List<AnalyticsMessages.MixpanelDescription> messages = new ArrayList<AnalyticsMessages.MixpanelDescription>();
        final BlockingQueue<JSONObject> anonymousUpdates = new LinkedBlockingQueue();
        final BlockingQueue<JSONObject> peopleUpdates = new LinkedBlockingQueue();

        final MPDbAdapter mockAdapter = new MPDbAdapter(getContext()) {
            @Override
            public int addJSON(JSONObject j, String token, Table table, boolean isAutomaticRecord) {
                if (table == Table.ANONYMOUS_PEOPLE) {
                    anonymousUpdates.add(j);
                } else if (table == Table.PEOPLE) {
                    peopleUpdates.add(j);
                }
                return super.addJSON(j, token, table, isAutomaticRecord);
            }
        };
        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            public void peopleMessage(PeopleDescription heard) {
                messages.add(heard);
                super.peopleMessage(heard);
            }

            @Override
            public void pushAnonymousPeopleMessage(PushAnonymousPeopleDescription pushAnonymousPeopleDescription) {
                messages.add(pushAnonymousPeopleDescription);
                super.pushAnonymousPeopleMessage(pushAnonymousPeopleDescription);
            }

            @Override
            protected MPDbAdapter makeDbAdapter(Context context) {
                return mockAdapter;
            }
        };

        MixpanelAPI mixpanel = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, token) {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }

            @Override
            DecideMessages constructDecideUpdates(String token, DecideMessages.OnNewResultsListener listener, UpdatesFromMixpanel updatesFromMixpanel) {
                return super.constructDecideUpdates(token, listener, updatesFromMixpanel);
            }
        };

        MixpanelAPI.People people = mixpanel.getPeople();
        people.increment("the prop", 0L);
        people.append("the prop", 1);
        people.set("the prop", 2);
        people.increment("the prop", 3L);
        people.append("the prop", 5);

        assertEquals(0L, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$add").getLong("the prop"));
        assertEquals(1, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$append").get("the prop"));
        assertEquals(2, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$set").get("the prop"));
        assertEquals(3L, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$add").getLong("the prop"));
        assertEquals(5, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$append").get("the prop"));
        assertNull(anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));
        assertNull(peopleUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));

        people.identify("Personal Identity");
        people.set("the prop identified", "prop value identified");

        assertEquals("prop value identified", peopleUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$set").getString("the prop identified"));
        assertNull(peopleUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));
        assertNull(anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));

        String[] storedAnonymous = mockAdapter.generateDataString(MPDbAdapter.Table.ANONYMOUS_PEOPLE, token,false);
        assertNull(storedAnonymous);

        String[] storedPeople = mockAdapter.generateDataString(MPDbAdapter.Table.PEOPLE, token,false);
        assertEquals(6, Integer.valueOf(storedPeople[2]).intValue());
        JSONArray data = new JSONArray(storedPeople[1]);
        for (int i=0; i < data.length(); i++) {
            JSONObject j = data.getJSONObject(i);
            assertEquals("Personal Identity", j.getString("$distinct_id"));
        }
    }

    public void testIdentifyAndGetDistinctId() {
        MixpanelAPI metrics = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, "Identify Test Token");

        String generatedId = metrics.getDistinctId();
        assertNotNull(generatedId);

        String emptyId = metrics.getPeople().getDistinctId();
        assertNull(emptyId);

        metrics.identify("Events Id");
        String setId = metrics.getDistinctId();
        assertEquals("Events Id", setId);

        String userId = metrics.getUserId();
        assertEquals("Events Id", userId);

        String stillEmpty = metrics.getPeople().getDistinctId();
        assertNull(stillEmpty);

        metrics.getPeople().identify("People Id");
        String unchangedId = metrics.getDistinctId();
        assertEquals("Events Id", unchangedId);

        String setPeopleId = metrics.getPeople().getDistinctId();
        assertEquals("People Id", setPeopleId);
    }

    public void testIdentifyAndCheckUserIDAndDeviceID() {
        MixpanelAPI metrics = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, "Identify Test Token");

        String generatedId = metrics.getAnonymousId();
        assertNotNull(generatedId);
        String eventsDistinctId = metrics.getDistinctId();
        assertNotNull(eventsDistinctId);
        assertEquals(eventsDistinctId, generatedId);
        assertNull(metrics.getUserId());

        String emptyId = metrics.getPeople().getDistinctId();
        assertNull(emptyId);

        metrics.identify("Events Id");
        String setId = metrics.getDistinctId();
        assertEquals("Events Id", setId);
        String anonymousIdAfterIdentify = metrics.getAnonymousId();
        assertEquals(anonymousIdAfterIdentify, generatedId);

        String stillEmpty = metrics.getPeople().getDistinctId();
        assertNull(stillEmpty);

        metrics.getPeople().identify("People Id");
        String unchangedId = metrics.getDistinctId();
        assertEquals("Events Id", unchangedId);

        String setPeopleId = metrics.getPeople().getDistinctId();
        assertEquals("People Id", setPeopleId);

        // once its reset we will only have generated id but user id should be null
        metrics.reset();
        String generatedId2 = metrics.getAnonymousId();
        assertNotNull(generatedId2);
        assertNotSame(generatedId, generatedId2);
        assertNotNull(metrics.getDistinctId());
        assertNull(metrics.getUserId());
    }

    public void testMessageQueuing() {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<String>();
        final SynchronizedReference<Boolean> isIdentifiedRef = new SynchronizedReference<Boolean>();
        isIdentifiedRef.set(false);

        final MPDbAdapter mockAdapter = new MPDbAdapter(getContext()) {
            @Override
            public int addJSON(JSONObject message, String token, MPDbAdapter.Table table, boolean isAutomaticEvent) {
                try {
                    if (!isAutomaticEvent) {
                        messages.put("TABLE " + table.getName());
                        messages.put(message.toString());
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                return super.addJSON(message, token, table, isAutomaticEvent);
            }
        };
        mockAdapter.cleanupEvents(Long.MAX_VALUE, MPDbAdapter.Table.EVENTS);
        mockAdapter.cleanupEvents(Long.MAX_VALUE, MPDbAdapter.Table.PEOPLE);

        final RemoteService mockPoster = new HttpService() {
            @Override
            public byte[] performRequest(String endpointUrl, Map<String, Object> params, SSLSocketFactory socketFactory) {
                final boolean isIdentified = isIdentifiedRef.get();
                if (null == params) {
                    if (isIdentified) {
                        assertEquals("DECIDE_ENDPOINT?version=1&lib=android&token=Test+Message+Queuing&distinct_id=PEOPLE+ID" + mAppProperties, endpointUrl);
                    } else {
                        assertEquals("DECIDE_ENDPOINT?version=1&lib=android&token=Test+Message+Queuing&distinct_id=EVENTS+ID" + mAppProperties, endpointUrl);
                    }
                    return TestUtils.bytes("{\"notifications\":[{\"body\":\"A\",\"image_tint_color\":4294967295,\"border_color\":4294967295,\"message_id\":85151,\"bg_color\":3858759680,\"extras\":{},\"image_url\":\"https://cdn.mxpnl.com/site_media/images/engage/inapp_messages/mini/icon_megaphone.png\",\"cta_url\":null,\"type\":\"mini\",\"id\":1191793,\"body_color\":4294967295, \"display_triggers\":[{\"event\":\"test_event\"}]}]}");
                }

                assertTrue(params.containsKey("data"));
                final String decoded = Base64Coder.decodeString(params.get("data").toString());

                try {
                    messages.put("SENT FLUSH " + endpointUrl);
                    messages.put(decoded);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                return TestUtils.bytes("1\n");
            }
        };


        final MPConfig mockConfig = new MPConfig(new Bundle(), getContext()) {
            @Override
            public int getFlushInterval() {
                return -1;
            }

            @Override
            public int getBulkUploadLimit() {
                return 40;
            }

            @Override
            public String getEventsEndpoint() {
                return "EVENTS_ENDPOINT";
            }

            @Override
            public String getPeopleEndpoint() {
                return "PEOPLE_ENDPOINT";
            }

            @Override
            public String getGroupsEndpoint() {
                return "GROUPS_ENDPOINT";
            }

            @Override
            public String getDecideEndpoint() {
                return "DECIDE_ENDPOINT";
            }

            @Override
            public boolean getDisableAppOpenEvent() { return true; }
        };

        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            protected MPDbAdapter makeDbAdapter(Context context) {
                return mockAdapter;
            }

            @Override
            protected MPConfig getConfig(Context context) {
                return mockConfig;
            }

            @Override
            protected RemoteService getPoster() {
                return mockPoster;
            }
        };

        MixpanelAPI metrics = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, "Test Message Queuing") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                 return listener;
            }
        };

        metrics.identify("EVENTS ID");

        // Test filling up the message queue
        for (int i=0; i < mockConfig.getBulkUploadLimit() - 2; i++) {
            metrics.track("frequent event", null);
        }

        metrics.track("final event", null);
        String expectedJSONMessage = "<No message actually received>";

        try {
            String messageTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("TABLE " + MPDbAdapter.Table.EVENTS.getName(), messageTable);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONObject message = new JSONObject(expectedJSONMessage);
            assertEquals("$identify", message.getString("event"));

            for (int i=0; i < mockConfig.getBulkUploadLimit() - 2; i++) {
                messageTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
                assertEquals("TABLE " + MPDbAdapter.Table.EVENTS.getName(), messageTable);

                expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
                message = new JSONObject(expectedJSONMessage);
                assertEquals("frequent event", message.getString("event"));
            }

            messageTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("TABLE " + MPDbAdapter.Table.EVENTS.getName(), messageTable);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            message = new JSONObject(expectedJSONMessage);
            assertEquals("final event", message.getString("event"));

            String messageFlush = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("SENT FLUSH EVENTS_ENDPOINT", messageFlush);

            assertTrue(metrics.getDecideMessages().hasNotificationsAvailable());

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONArray bigFlush = new JSONArray(expectedJSONMessage);
            assertEquals(mockConfig.getBulkUploadLimit(), bigFlush.length());

            metrics.track("next wave", null);
            metrics.flush();

            String nextWaveTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("TABLE " + MPDbAdapter.Table.EVENTS.getName(), nextWaveTable);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONObject nextWaveMessage = new JSONObject(expectedJSONMessage);
            assertEquals("next wave", nextWaveMessage.getString("event"));

            String manualFlush = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("SENT FLUSH EVENTS_ENDPOINT", manualFlush);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONArray nextWave = new JSONArray(expectedJSONMessage);
            assertEquals(1, nextWave.length());

            JSONObject nextWaveEvent = nextWave.getJSONObject(0);
            assertEquals("next wave", nextWaveEvent.getString("event"));

            isIdentifiedRef.set(true);
            metrics.getPeople().identify("PEOPLE ID");
            metrics.getPeople().set("prop", "yup");
            metrics.flush();

            String peopleTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("TABLE " + MPDbAdapter.Table.PEOPLE.getName(), peopleTable);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONObject peopleMessage = new JSONObject(expectedJSONMessage);

            assertEquals("PEOPLE ID", peopleMessage.getString("$distinct_id"));
            assertEquals("yup", peopleMessage.getJSONObject("$set").getString("prop"));

            String peopleFlush = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("SENT FLUSH PEOPLE_ENDPOINT", peopleFlush);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONArray peopleSent = new JSONArray(expectedJSONMessage);
            assertEquals(1, peopleSent.length());

            metrics.getGroup("testKey", "testID").set("prop", "yup");
            metrics.flush();

            String groupsTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("TABLE " + MPDbAdapter.Table.GROUPS.getName(), groupsTable);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONObject groupsMessage = new JSONObject(expectedJSONMessage);

            assertEquals("testKey", groupsMessage.getString("$group_key"));
            assertEquals("testID", groupsMessage.getString("$group_id"));
            assertEquals("yup", groupsMessage.getJSONObject("$set").getString("prop"));

            String groupsFlush = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("SENT FLUSH GROUPS_ENDPOINT", groupsFlush);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONArray groupsSent = new JSONArray(expectedJSONMessage);
            assertEquals(1, groupsSent.length());
        } catch (InterruptedException e) {
            fail("Expected a log message about mixpanel communication but did not receive it.");
        } catch (JSONException e) {
            fail("Expected a JSON object message and got something silly instead: " + expectedJSONMessage);
        }
    }

    public void testTrackCharge() {
        final List<AnalyticsMessages.PeopleDescription> messages = new ArrayList<>();
        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            public void eventsMessage(EventDescription heard) {
                if (!heard.isAutomatic()) {
                    throw new RuntimeException("Should not be called during this test");
                }
            }

            @Override
            public void peopleMessage(PeopleDescription heard) {
                messages.add(heard);
            }
        };

        class ListeningAPI extends TestUtils.CleanMixpanelAPI {
            public ListeningAPI(Context c, Future<SharedPreferences> referrerPrefs, String token) {
                super(c, referrerPrefs, token);
            }

            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                 return listener;
            }
        }

        MixpanelAPI api = new ListeningAPI(getContext(), mMockPreferences, "TRACKCHARGE TEST TOKEN");
        api.getPeople().identify("TRACKCHARGE PERSON");

        JSONObject props;
        try {
            props = new JSONObject("{'$time':'Should override', 'Orange':'Banana'}");
        } catch (JSONException e) {
            throw new RuntimeException("Can't construct fixture for trackCharge test");
        }

        api.getPeople().trackCharge(2.13, props);
        assertEquals(messages.size(), 1);

        JSONObject message = messages.get(0).getMessage();

        try {
            JSONObject append = message.getJSONObject("$append");
            JSONObject newTransaction = append.getJSONObject("$transactions");
            assertEquals(newTransaction.optString("Orange"), "Banana");
            assertEquals(newTransaction.optString("$time"), "Should override");
            assertEquals(newTransaction.optDouble("$amount"), 2.13);
        } catch (JSONException e) {
            fail("Transaction message had unexpected layout:\n" + message.toString());
        }
    }

    public void testTrackWithSavedDistinctId(){
        final String savedDistinctID = "saved_distinct_id";
        final List<Object> messages = new ArrayList<Object>();
        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            public void eventsMessage(EventDescription heard) {
                if (!heard.isAutomatic() && !heard.getEventName().equals("$identify")) {
                    messages.add(heard);
                }
            }

            @Override
            public void peopleMessage(PeopleDescription heard) {
                messages.add(heard);
            }
        };

        class TestMixpanelAPI extends MixpanelAPI {
            public TestMixpanelAPI(Context c, Future<SharedPreferences> prefs, String token) {
                super(c, prefs, token, false, null);
            }

            @Override
            /* package */ PersistentIdentity getPersistentIdentity(final Context context, final Future<SharedPreferences> referrerPreferences, final String token) {
                final String mixpanelPrefsName = "com.mixpanel.android.mpmetrics.Mixpanel";
                final SharedPreferences mpSharedPrefs = context.getSharedPreferences(mixpanelPrefsName, Context.MODE_PRIVATE);
                mpSharedPrefs.edit().clear().putBoolean(token, true).putBoolean("has_launched", true).commit();
                final String prefsName = "com.mixpanel.android.mpmetrics.MixpanelAPI_" + token;
                final SharedPreferences loadstorePrefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
                loadstorePrefs.edit().clear().putString("events_distinct_id", savedDistinctID).putString("people_distinct_id", savedDistinctID).commit();
                return super.getPersistentIdentity(context, referrerPreferences, token);
                }

            @Override
            /* package */ boolean sendAppOpen() {
                return false;
            }

            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        }

        TestMixpanelAPI mpMetrics = new TestMixpanelAPI(getContext(), mMockPreferences, "SAME TOKEN");
        assertEquals(mpMetrics.getDistinctId(), savedDistinctID);
        mpMetrics.identify("new_user");

        mpMetrics.track("eventname", null);
        mpMetrics.getPeople().set("people prop name", "Indeed");

        assertEquals(2, messages.size());

        AnalyticsMessages.EventDescription eventMessage = (AnalyticsMessages.EventDescription) messages.get(0);
        JSONObject peopleMessage =  ((AnalyticsMessages.PeopleDescription)messages.get(1)).getMessage();

        try {
            JSONObject eventProps = eventMessage.getProperties();
            String deviceId = eventProps.getString("$device_id");
            assertEquals(savedDistinctID, deviceId);
            boolean hadPersistedDistinctId = eventProps.getBoolean("$had_persisted_distinct_id");
            assertEquals(true, hadPersistedDistinctId);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }

        try {
            String deviceId = peopleMessage.getString("$device_id");
            boolean hadPersistedDistinctId = peopleMessage.getBoolean("$had_persisted_distinct_id");
            assertEquals(savedDistinctID, deviceId);
            assertEquals(true, hadPersistedDistinctId);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }
        messages.clear();
    }

    public void testSetAddRemoveGroup(){
        final String savedDistinctID = "saved_distinct_id";
        final List<Object> messages = new ArrayList<Object>();
        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            public void eventsMessage(EventDescription heard) {
                if (!heard.isAutomatic() &&
                        !heard.getEventName().equals("$identify") &&
                        !heard.getEventName().equals("Integration")) {
                    messages.add(heard);
                }
            }

            @Override
            public void peopleMessage(PeopleDescription heard) {
                messages.add(heard);
            }
        };

        class TestMixpanelAPI extends MixpanelAPI {
            public TestMixpanelAPI(Context c, Future<SharedPreferences> prefs, String token) {
                super(c, prefs, token, false, null);
            }

            @Override
                /* package */ boolean sendAppOpen() {
                return false;
            }

            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        }

        TestMixpanelAPI mpMetrics = new TestMixpanelAPI(getContext(), mMockPreferences, "SAME TOKEN");
        mpMetrics.identify("new_user");

        int groupID = 42;
        mpMetrics.setGroup("group_key", groupID);
        mpMetrics.track("eventname", null);

        assertEquals(2, messages.size());

        JSONObject peopleMessage =  ((AnalyticsMessages.PeopleDescription)messages.get(0)).getMessage();
        AnalyticsMessages.EventDescription eventMessage = (AnalyticsMessages.EventDescription) messages.get(1);

        try {
            JSONObject eventProps = eventMessage.getProperties();
            JSONArray groupIDs = eventProps.getJSONArray("group_key");
            assertEquals((new JSONArray()).put(groupID), groupIDs);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }

        try {
            JSONObject setMessage = peopleMessage.getJSONObject("$set");
            assertEquals((new JSONArray()).put(groupID), setMessage.getJSONArray("group_key"));
        } catch (JSONException e) {
            fail("People message has an unexpected shape " + e);
        }

        messages.clear();

        int groupID2 = 77;
        mpMetrics.addGroup("group_key", groupID2);
        mpMetrics.track("eventname", null);
        JSONArray expectedGroupIDs = new JSONArray();
        expectedGroupIDs.put(groupID);
        expectedGroupIDs.put(groupID2);

        assertEquals(2, messages.size());
        peopleMessage =  ((AnalyticsMessages.PeopleDescription)messages.get(0)).getMessage();
        eventMessage = (AnalyticsMessages.EventDescription) messages.get(1);

        try {
            JSONObject eventProps = eventMessage.getProperties();
            JSONArray groupIDs = eventProps.getJSONArray("group_key");
            assertEquals(expectedGroupIDs, groupIDs);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }

        try {
            JSONObject unionMessage = peopleMessage.getJSONObject("$union");
            assertEquals((new JSONArray()).put(groupID2), unionMessage.getJSONArray("group_key"));
        } catch (JSONException e) {
            fail("People message has an unexpected shape " + e);
        }

        messages.clear();
        mpMetrics.removeGroup("group_key", groupID2);
        mpMetrics.track("eventname", null);

        assertEquals(2, messages.size());
        peopleMessage =  ((AnalyticsMessages.PeopleDescription)messages.get(0)).getMessage();
        eventMessage = (AnalyticsMessages.EventDescription) messages.get(1);

        try {
            JSONObject eventProps = eventMessage.getProperties();
            JSONArray groupIDs = eventProps.getJSONArray("group_key");
            assertEquals((new JSONArray()).put(groupID), groupIDs);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }

        try {
            JSONObject removeMessage = peopleMessage.getJSONObject("$remove");
            assertEquals(groupID2, removeMessage.getInt("group_key"));
        } catch (JSONException e) {
            fail("People message has an unexpected shape " + e);
        }

        messages.clear();
        mpMetrics.removeGroup("group_key", groupID);
        mpMetrics.track("eventname", null);

        assertEquals(2, messages.size());
        peopleMessage =  ((AnalyticsMessages.PeopleDescription)messages.get(0)).getMessage();
        eventMessage = (AnalyticsMessages.EventDescription) messages.get(1);

        JSONObject eventProps = eventMessage.getProperties();
        assertFalse(eventProps.has("group_key"));

        try {
            JSONArray unsetMessage = peopleMessage.getJSONArray("$unset");
            assertEquals(1, unsetMessage.length());
            assertEquals("group_key", unsetMessage.get(0));
        } catch (JSONException e) {
            fail("People message has an unexpected shape " + e);
        }

        messages.clear();
    }

    public void testIdentifyCall() throws JSONException {
        String newDistinctId = "New distinct ID";
        final List<AnalyticsMessages.EventDescription> messages = new ArrayList<AnalyticsMessages.EventDescription>();
        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            public void eventsMessage(EventDescription heard) {
                if (!heard.isAutomatic()) {
                    messages.add(heard);
                }
            }
        };


        MixpanelAPI metrics = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, "Test Identify Call") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        };
        String oldDistinctId = metrics.getDistinctId();
        metrics.identify(newDistinctId);
        metrics.identify(newDistinctId);
        metrics.identify(newDistinctId);

        assertEquals(messages.size(), 1);
        AnalyticsMessages.EventDescription identifyEventDescription = messages.get(0);
        assertEquals(identifyEventDescription.getEventName(), "$identify");
        String newDistinctIdIdentifyTrack = identifyEventDescription.getProperties().getString("distinct_id");
        String anonDistinctIdIdentifyTrack = identifyEventDescription.getProperties().getString("$anon_distinct_id");

        assertEquals(newDistinctIdIdentifyTrack, newDistinctId);
        assertEquals(anonDistinctIdIdentifyTrack, oldDistinctId);
        assertEquals(messages.size(), 1);
    }

    public void testIdentifyResetCall() throws JSONException {
        String newDistinctId = "New distinct ID";
        final List<AnalyticsMessages.EventDescription> messages = new ArrayList<AnalyticsMessages.EventDescription>();
        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            public void eventsMessage(EventDescription heard) {
                if (!heard.isAutomatic()) {
                    messages.add(heard);
                }
            }
        };

        MixpanelAPI metrics = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, "Test Identify Call") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        };
        ArrayList<String> oldDistinctIds = new ArrayList<>();
        oldDistinctIds.add(metrics.getDistinctId());
        metrics.identify(newDistinctId + "0");
        metrics.reset();
        oldDistinctIds.add(metrics.getDistinctId());
        metrics.identify(newDistinctId + "1");
        metrics.reset();

        oldDistinctIds.add(metrics.getDistinctId());
        metrics.identify(newDistinctId + "2");

        assertEquals(messages.size(), 3);
        for (int i=0; i < 3; i++) {
            AnalyticsMessages.EventDescription identifyEventDescription = messages.get(i);
            assertEquals(identifyEventDescription.getEventName(), "$identify");
            String newDistinctIdIdentifyTrack = identifyEventDescription.getProperties().getString("distinct_id");
            String anonDistinctIdIdentifyTrack = identifyEventDescription.getProperties().getString("$anon_distinct_id");

            assertEquals(newDistinctIdIdentifyTrack, newDistinctId + String.valueOf(i));
            assertEquals(anonDistinctIdIdentifyTrack, oldDistinctIds.get(i));
        }
    }

    public void testPersistence() {
        MixpanelAPI metricsOne = new MixpanelAPI(getContext(), mMockPreferences, "SAME TOKEN", false, null);
        metricsOne.reset();

        JSONObject props;
        try {
            props = new JSONObject("{ 'a' : 'value of a', 'b' : 'value of b' }");
        } catch (JSONException e) {
            throw new RuntimeException("Can't construct fixture for super properties test.");
        }

        metricsOne.clearSuperProperties();
        metricsOne.registerSuperProperties(props);
        metricsOne.identify("Expected Events Identity");
        metricsOne.getPeople().identify("Expected People Identity");

        // We exploit the fact that any metrics object with the same token
        // will get their values from the same persistent store.

        final List<Object> messages = new ArrayList<Object>();
        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            public void eventsMessage(EventDescription heard) {
                if (!heard.isAutomatic()) {
                    messages.add(heard);
                }
            }

            @Override
            public void peopleMessage(PeopleDescription heard) {
                messages.add(heard);
            }
        };

        class ListeningAPI extends MixpanelAPI {
            public ListeningAPI(Context c, Future<SharedPreferences> prefs, String token) {
                super(c, prefs, token, false, null);
            }

            @Override
        /* package */ PersistentIdentity getPersistentIdentity(final Context context, final Future<SharedPreferences> referrerPreferences, final String token) {
                final String mixpanelPrefsName = "com.mixpanel.android.mpmetrics.Mixpanel";
                final SharedPreferences mpSharedPrefs = context.getSharedPreferences(mixpanelPrefsName, Context.MODE_PRIVATE);
                mpSharedPrefs.edit().clear().putBoolean(token, true).putBoolean("has_launched", true).commit();

                return super.getPersistentIdentity(context, referrerPreferences, token);
            }

            @Override
            /* package */ boolean sendAppOpen() {
                return false;
            }

            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                 return listener;
            }
        }

        MixpanelAPI differentToken = new ListeningAPI(getContext(), mMockPreferences, "DIFFERENT TOKEN");

        differentToken.track("other event", null);
        differentToken.getPeople().set("other people prop", "Word"); // should be queued up.

        assertEquals(2, messages.size());

        AnalyticsMessages.EventDescription eventMessage = (AnalyticsMessages.EventDescription) messages.get(0);

        try {
            JSONObject eventProps = eventMessage.getProperties();
            String sentId = eventProps.getString("distinct_id");
            String sentA = eventProps.optString("a");
            String sentB = eventProps.optString("b");

            assertFalse("Expected Events Identity".equals(sentId));
            assertEquals("", sentA);
            assertEquals("", sentB);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }

        messages.clear();

        MixpanelAPI metricsTwo = new ListeningAPI(getContext(), mMockPreferences, "SAME TOKEN");

        metricsTwo.track("eventname", null);
        metricsTwo.getPeople().set("people prop name", "Indeed");

        assertEquals(2, messages.size());

        eventMessage = (AnalyticsMessages.EventDescription) messages.get(0);
        JSONObject peopleMessage =  ((AnalyticsMessages.PeopleDescription)messages.get(1)).getMessage();

        try {
            JSONObject eventProps = eventMessage.getProperties();
            String sentId = eventProps.getString("distinct_id");
            String sentA = eventProps.getString("a");
            String sentB = eventProps.getString("b");

            assertEquals("Expected Events Identity", sentId);
            assertEquals("value of a", sentA);
            assertEquals("value of b", sentB);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }

        try {
            String sentId = peopleMessage.getString("$distinct_id");
            assertEquals("Expected People Identity", sentId);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape: " + peopleMessage.toString());
        }
    }

    public void testTrackInThread() throws InterruptedException, JSONException {
        class TestThread extends Thread {
            BlockingQueue<JSONObject> mMessages;

            public TestThread(BlockingQueue<JSONObject> messages) {
                this.mMessages = messages;
            }

            @Override
            public void run() {

                final MPDbAdapter dbMock = new MPDbAdapter(getContext()) {
                    @Override
                    public int addJSON(JSONObject message, String token, MPDbAdapter.Table table, boolean isAutomatic) {
                        if (!isAutomatic) {
                            mMessages.add(message);
                        }

                        return 1;
                    }
                };

                final AnalyticsMessages analyticsMessages = new AnalyticsMessages(getContext()) {
                    @Override
                    public MPDbAdapter makeDbAdapter(Context context) {
                        return dbMock;
                    }
                };

                MixpanelAPI mixpanel = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, "TEST TOKEN") {
                    @Override
                    protected AnalyticsMessages getAnalyticsMessages() {
                        return analyticsMessages;
                    }
                };
                mixpanel.reset();
                mixpanel.track("test in thread", new JSONObject());
            }
        }

        //////////////////////////////

        final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<JSONObject>();
        TestThread testThread = new TestThread(messages);
        testThread.start();
        JSONObject found = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(found);
        assertEquals(found.getString("event"), "test in thread");
        assertTrue(found.getJSONObject("properties").has("$bluetooth_version"));
    }

    public void testConfiguration() {
        final ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.metaData = new Bundle();
        appInfo.metaData.putInt("com.mixpanel.android.MPConfig.BulkUploadLimit", 1);
        appInfo.metaData.putInt("com.mixpanel.android.MPConfig.FlushInterval", 2);
        appInfo.metaData.putInt("com.mixpanel.android.MPConfig.DataExpiration", 3);
        appInfo.metaData.putBoolean("com.mixpanel.android.MPConfig.AutoShowMixpanelUpdates", false);
        appInfo.metaData.putBoolean("com.mixpanel.android.MPConfig.DisableGestureBindingUI", true);
        appInfo.metaData.putBoolean("com.mixpanel.android.MPConfig.DisableEmulatorBindingUI", true);
        appInfo.metaData.putBoolean("com.mixpanel.android.MPConfig.DisableAppOpenEvent", true);

        appInfo.metaData.putString("com.mixpanel.android.MPConfig.EventsEndpoint", "EVENTS ENDPOINT");
        appInfo.metaData.putString("com.mixpanel.android.MPConfig.PeopleEndpoint", "PEOPLE ENDPOINT");
        appInfo.metaData.putString("com.mixpanel.android.MPConfig.GroupsEndpoint", "GROUPS ENDPOINT");
        appInfo.metaData.putString("com.mixpanel.android.MPConfig.DecideEndpoint", "DECIDE ENDPOINT");

        final PackageManager packageManager = new MockPackageManager() {
            @Override
            public ApplicationInfo getApplicationInfo(String packageName, int flags) {
                assertEquals(packageName, "TEST PACKAGE NAME");
                assertTrue((flags & PackageManager.GET_META_DATA) == PackageManager.GET_META_DATA);
                return appInfo;
            }
        };

        final Context context = new MockContext() {
            @Override
            public String getPackageName() {
                return "TEST PACKAGE NAME";
            }

            @Override
            public PackageManager getPackageManager() {
                return packageManager;
            }
        };

        final MPConfig testConfig = MPConfig.readConfig(context);
        assertEquals(1, testConfig.getBulkUploadLimit());
        assertEquals(2, testConfig.getFlushInterval());
        assertEquals(3, testConfig.getDataExpiration());
        assertEquals(true, testConfig.getDisableEmulatorBindingUI());
        assertEquals(true, testConfig.getDisableGestureBindingUI());
        assertEquals(true, testConfig.getDisableAppOpenEvent());
        assertEquals(false, testConfig.getAutoShowMixpanelUpdates());
        assertEquals("EVENTS ENDPOINT", testConfig.getEventsEndpoint());
        assertEquals("PEOPLE ENDPOINT", testConfig.getPeopleEndpoint());
        assertEquals("DECIDE ENDPOINT", testConfig.getDecideEndpoint());
    }

    public void test2XUrls() {
        final String twoXBalok = InAppNotification.sizeSuffixUrl("http://images.mxpnl.com/112690/1392337640909.49573.Balok_first.jpg", "@BANANAS");
        assertEquals(twoXBalok, "http://images.mxpnl.com/112690/1392337640909.49573.Balok_first@BANANAS.jpg");

        final String nothingMatches = InAppNotification.sizeSuffixUrl("http://images.mxpnl.com/112690/1392337640909.49573.Balok_first..", "@BANANAS");
        assertEquals(nothingMatches, "http://images.mxpnl.com/112690/1392337640909.49573.Balok_first..");

        final String emptyMatch = InAppNotification.sizeSuffixUrl("", "@BANANAS");
        assertEquals(emptyMatch, "");

        final String nothingExtensionful = InAppNotification.sizeSuffixUrl("http://images.mxpnl.com/112690/", "@BANANAS");
        assertEquals(nothingExtensionful, "http://images.mxpnl.com/112690/");
    }

    public void testAlias() {
        final RemoteService mockPoster = new HttpService() {
            @Override
            public byte[] performRequest(String endpointUrl, Map<String, Object> params, SSLSocketFactory socketFactory) {
                try {
                    assertTrue(params.containsKey("data"));
                    final String jsonData = Base64Coder.decodeString(params.get("data").toString());
                    JSONArray msg = new JSONArray(jsonData);
                    JSONObject event = msg.getJSONObject(0);
                    JSONObject properties = event.getJSONObject("properties");

                    assertEquals(event.getString("event"), "$create_alias");
                    assertEquals(properties.getString("distinct_id"), "old id");
                    assertEquals(properties.getString("alias"), "new id");
                } catch (JSONException e) {
                    throw new RuntimeException("Malformed data passed to test mock", e);
                }
                return TestUtils.bytes("1\n");
            }
        };

        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            protected RemoteService getPoster() {
                return mockPoster;
            }
        };

        MixpanelAPI metrics = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, "Test Message Queuing") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                 return listener;
            }
        };

        // Check that we post the alias immediately
        metrics.identify("old id");
        metrics.alias("new id", "old id");
    }

    public void testAnonymousPeopleUpdates() throws InterruptedException, JSONException {
        final BlockingQueue<JSONObject> anonymousUpdates = new LinkedBlockingQueue<JSONObject>();
        final BlockingQueue<JSONObject> identifiedUpdates = new LinkedBlockingQueue<JSONObject>();

        final MPDbAdapter mockAdapter = new MPDbAdapter(getContext()) {
            @Override
            public int addJSON(JSONObject j, String token, Table table, boolean isAutomaticRecord) {
                if (table == Table.ANONYMOUS_PEOPLE) {
                    anonymousUpdates.add(j);
                } else if (table == Table.PEOPLE) {
                    identifiedUpdates.add(j);
                }
                return super.addJSON(j, token, table, isAutomaticRecord);
            }
        };

        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            protected MPDbAdapter makeDbAdapter(Context context) {
                return mockAdapter;
            }
        };

        MixpanelAPI mixpanel = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, "testAnonymousPeopleUpdates") {
            @Override
            AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }
        };
        mixpanel.getPeople().set("firstProperty", "firstValue");
        mixpanel.getPeople().increment("incrementProperty", 3L);
        mixpanel.getPeople().append("appendProperty", "appendPropertyValue");
        mixpanel.getPeople().unset("unSetProperty");
        assertEquals("firstValue", anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$set").getString("firstProperty"));
        assertEquals(3L, anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$add").getLong("incrementProperty"));
        assertEquals("appendPropertyValue", anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$append").getString("appendProperty"));
        assertEquals("[\"unSetProperty\"]", anonymousUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONArray("$unset").toString());
        assertEquals(0, anonymousUpdates.size());
        assertEquals(0, identifiedUpdates.size());

        mixpanel.getPeople().identify("mixpanel_distinct_id");
        mixpanel.getPeople().set("firstPropertyIdentified", "firstValue");
        mixpanel.getPeople().increment("incrementPropertyIdentified", 3L);
        mixpanel.getPeople().append("appendPropertyIdentified", "appendPropertyValue");
        mixpanel.getPeople().unset("unSetPropertyIdentified");
        assertEquals("firstValue", identifiedUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$set").getString("firstPropertyIdentified"));
        assertEquals(3L, identifiedUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$add").getLong("incrementPropertyIdentified"));
        assertEquals("appendPropertyValue", identifiedUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$append").getString("appendPropertyIdentified"));
        assertEquals("[\"unSetPropertyIdentified\"]", identifiedUpdates.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONArray("$unset").toString());
        assertEquals(0, anonymousUpdates.size());
    }


    public void testSessionMetadata() throws InterruptedException, JSONException {
        final BlockingQueue<JSONObject> storedJsons = new LinkedBlockingQueue<>();
        final BlockingQueue<AnalyticsMessages.EventDescription> eventsMessages = new LinkedBlockingQueue<>();
        final BlockingQueue<AnalyticsMessages.PeopleDescription> peopleMessages = new LinkedBlockingQueue<>();
        final MPDbAdapter mockAdapter = new MPDbAdapter(getContext()) {

            @Override
            public int addJSON(JSONObject j, String token, Table table, boolean isAutomaticRecord) {
                storedJsons.add(j);
                return super.addJSON(j, token, table, isAutomaticRecord);
            }
        };
        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            public void eventsMessage(EventDescription eventDescription) {
                if (!eventDescription.isAutomatic()) {
                    eventsMessages.add(eventDescription);
                    super.eventsMessage(eventDescription);
                }
            }

            @Override
            public void peopleMessage(PeopleDescription peopleDescription) {
                peopleMessages.add(peopleDescription);
                super.peopleMessage(peopleDescription);
            }

            @Override
            protected MPDbAdapter makeDbAdapter(Context context) {
                return mockAdapter;
            }
        };
        MixpanelAPI metrics = new TestUtils.CleanMixpanelAPI(getContext(), mMockPreferences, "Test Session Metadata") {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }

            @Override
            protected void track(String eventName, JSONObject properties, boolean isAutomaticEvent) {
                if (!isAutomaticEvent) {
                    super.track(eventName, properties, isAutomaticEvent);
                }
            }
        };

        metrics.track("First Event");
        metrics.track("Second Event");
        metrics.track("Third Event");
        metrics.track("Fourth Event");

        metrics.getPeople().identify("Mixpanel");
        metrics.getPeople().set("setProperty", "setValue");
        metrics.getPeople().append("appendProperty", "appendValue");
        metrics.getPeople().deleteUser();

        for (int i = 0; i < 4; i++) {
            JSONObject sessionMetadata = eventsMessages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getSessionMetadata();
            assertTrue(sessionMetadata.has("$mp_event_id"));
            assertTrue(sessionMetadata.has("$mp_session_id"));
            assertTrue(sessionMetadata.has("$mp_session_start_sec"));

            assertEquals(i, sessionMetadata.getInt("$mp_session_seq_id"));
        }
        assertNull(eventsMessages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));

        for (int i = 0; i < 3; i++) {
            JSONObject sessionMetadata = peopleMessages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getMessage().getJSONObject("$mp_metadata");
            assertTrue(sessionMetadata.has("$mp_event_id"));
            assertTrue(sessionMetadata.has("$mp_session_id"));
            assertTrue(sessionMetadata.has("$mp_session_start_sec"));

            assertEquals(i, sessionMetadata.getInt("$mp_session_seq_id"));
        }
        assertNull(peopleMessages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));

        for (int i = 0; i < 4; i++) {
            JSONObject sessionMetadata = storedJsons.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$mp_metadata");
            assertTrue(sessionMetadata.has("$mp_event_id"));
            assertTrue(sessionMetadata.has("$mp_session_id"));
            assertTrue(sessionMetadata.has("$mp_session_start_sec"));

            assertEquals(i, sessionMetadata.getInt("$mp_session_seq_id"));
        }

        for (int i = 0; i < 3; i++) {
            JSONObject sessionMetadata = storedJsons.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$mp_metadata");
            assertTrue(sessionMetadata.has("$mp_event_id"));
            assertTrue(sessionMetadata.has("$mp_session_id"));
            assertTrue(sessionMetadata.has("$mp_session_start_sec"));

            assertEquals(i, sessionMetadata.getInt("$mp_session_seq_id"));
        }
        assertNull(storedJsons.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));
    }

    private Future<SharedPreferences> mMockPreferences;

    private static final int POLL_WAIT_SECONDS = 10;

    private String mAppProperties;
}
