package net.osmand.plus.activities;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.util.ArrayList;

import net.osmand.LogUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;

import org.apache.commons.logging.Log;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.os.AsyncTask;

public class RoadSpeakHelper {
	
	protected Context ctx;
	private OsmandSettings settings;
	private long lastTimeUpdated = 0L;
	private final static Log log = LogUtil.getLog(RoadSpeakHelper.class);
	
	private long onlineMemberCount = 0;
	private ArrayList<String> groupListName = new ArrayList<String>();
	
	public RoadSpeakHelper(Context ctx) {
		this.ctx = ctx;
		settings = ((OsmandApplication)ctx.getApplicationContext()).getSettings();
	}
	
	public boolean isKeepLoggedInEnabled(){
		return settings.ROADSPEAK_KEEP_LOGGED_IN.get();
	}
	
	public synchronized long getOnlineMemberCount() {
		return onlineMemberCount;
	}

	public synchronized ArrayList<String> getGroupListName() {
		return groupListName;
	}
	
	public void fetchData(long time){
		if(time - lastTimeUpdated > settings.ROADSPEAK_INTERVAL.get()*1000){
			new RoadSpeakFetcher().execute();
			lastTimeUpdated = time;
		}		
	}
	
	private class RoadSpeakFetcher extends AsyncTask<Void, Void, Void>{

		@Override
		protected Void doInBackground(Void... arg0) {
			fetchData();
			return null;
		}
		
	}

	public void fetchData() {
		String url = MessageFormat.format(settings.ROADSPEAK_UPDATE_URL.get(), settings.ROADSPEAK_USER_NAME.get(), settings.ROADSPEAK_USER_PASSWORD.get());
		try{
			HttpParams params = new BasicHttpParams();
			HttpConnectionParams.setConnectionTimeout(params, 15000);
			DefaultHttpClient httpclient = new DefaultHttpClient(params);
			HttpRequestBase method = new HttpGet(url);
			log.info("RoadSpeak Update " + url);
			HttpResponse response = httpclient.execute(method);
			
			if(response.getStatusLine() == null || response.getStatusLine().getStatusCode() != 200){
				String msg;
				if(response.getStatusLine() != null){
					msg = ctx.getString(R.string.failed_op);					
				}else{
					msg = response.getStatusLine().getStatusCode() + " : " + response.getStatusLine().getReasonPhrase();
				}
				log.error("Error fetching RoadSpeak update : " + msg);
			}else{
				InputStream is = response.getEntity().getContent();
				StringBuilder responseBody = new StringBuilder();
				if(is != null){
					BufferedReader in = new BufferedReader(new InputStreamReader(is, "UTF-8"));
					String s;
					while((s=in.readLine()) != null){
						responseBody.append(s);
					}
					is.close();
				}
				httpclient.getConnectionManager().shutdown();
				log.info("RoadSpeak Update response : " + responseBody.toString());
				
				updateData(responseBody.toString());
			}
		}catch(Exception e){
			log.error("Failed connect to " + url, e);
		}
				
	}

	private synchronized void updateData(String responseBody) {
		try {
			JSONObject o = new JSONObject(responseBody);
			onlineMemberCount = o.getLong(ctx.getString(R.string.roadspeak_online_member_count_key));
			JSONArray array = o.getJSONArray(ctx.getString(R.string.roadspeak_grouplist_name_key));
			groupListName.clear();
			for(int i = 0; i < array.length(); i++){
				groupListName.add(array.getString(i));
			}
		} catch (JSONException e) {
			log.error("Failed reading RoadSpeak response", e);
		}
		
	}
}
