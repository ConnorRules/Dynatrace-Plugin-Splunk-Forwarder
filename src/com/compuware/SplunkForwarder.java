
 /**
  * Created by Connor Gilbert
  * 
  * This plugin will export dashboard xml found at the following link
  * http://[server]:8020/rest/management/reports/create/[dashboard]?type=XML&format=XML+Export
  * 
  **/ 

package com.compuware;

import com.dynatrace.diagnostics.pdk.*;
import com.splunk.*;
import com.json.JSONObject;
import com.json.XML;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.io.*;
import java.net.*;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

public class SplunkForwarder implements Task {

	private static final Logger log = Logger.getLogger(SplunkForwarder.class.getName());
	private static Service service;

	/**
	 * Initializes the Plugin. This method is called in the following cases:
	 * <ul>
	 * <li>before <tt>execute</tt> is called the first time for this
	 * scheduled Plugin</li>
	 * <li>before the next <tt>execute</tt> if <tt>teardown</tt> was called
	 * after the last execution</li>
	 * </ul>
	 *
	 * <p>
	 * If the returned status is <tt>null</tt> or the status code is a
	 * non-success code then {@link Plugin#teardown() teardown()} will be called
	 * next.
	 *
	 * <p>
	 * Resources like sockets or files can be opened in this method.
	 *
	 * @param env
	 *            the configured <tt>TaskEnvironment</tt> for this Plugin
	 * @see Plugin#teardown()
	 * @return a <tt>Status</tt> object that describes the result of the
	 *         method call
	 */
	@Override
	public Status setup(TaskEnvironment env) throws Exception {
		
		//////////Log into the Splunk Server
        
		return new Status(Status.StatusCode.Success);
	}

	/**
	 * Executes the Task Plugin.
	 *
	 * <p>
	 * This method is called at the scheduled intervals. If the Plugin execution
	 * takes longer than the schedule interval, subsequent calls to
	 * {@link #execute(TaskEnvironment)} will be skipped until this method
	 * returns. After the execution duration exceeds the schedule timeout,
	 * {@link TaskEnvironment#isStopped()} will return <tt>true</tt>. In this
	 * case execution should be stopped as soon as possible. If the Plugin
	 * ignores {@link TaskEnvironment#isStopped()} or fails to stop execution in
	 * a reasonable timeframe, the execution thread will be stopped ungracefully
	 * which might lead to resource leaks!
	 *
	 * @param env
	 *            a <tt>TaskEnvironment</tt> object that contains the Plugin
	 *            configuration
	 * @return a <tt>Status</tt> object that describes the result of the
	 *         method call
	 */
	@Override
	public Status execute(TaskEnvironment env) throws Exception {

		File file = null;
		try{
		//Saves the dashboard xml to file 
        file = SaveURLToFile(env);
        if(file==null) return new Status(Status.StatusCode.ErrorDynatraceServer);
		}
		catch (Exception e) {
			log.info("Error loading dynatrace dasbhoard xml: " + e.getMessage());
			return new Status(Status.StatusCode.ErrorInternal);
		}
        
        try{
		 // Create a map of arguments and add login parameters
        ServiceArgs loginArgs = new ServiceArgs();
        loginArgs.setUsername(env.getConfigString("SplunkUsername"));
        loginArgs.setPassword(env.getConfigPassword("SplunkPassword"));
        loginArgs.setHost(env.getConfigString("SplunkServerAddress"));
        loginArgs.setPort(Integer.parseInt(env.getConfigString("SplunkPort")));
        
        // Create a Service instance and log in with the argument map
        service = Service.connect(loginArgs);
        log.info("Connected to Splunk Server Successfully");
        log.info("Token: " + service.getToken());
		}
        catch (Exception e){
        	log.info("Error trying to connect to Splunk server: " + e.getMessage());
        	return new Status(Status.StatusCode.ErrorTargetService);
        }
	
		
        //Get the writer for the send statements
        Writer out = GetSplunkOutputStream(env);
        if(out==null) return new Status(Status.StatusCode.ErrorTargetService);
        
        /////////Parse xml file and send to Splunk
        ParseXML(env, file, out);
        
        
        //Essentially the Teardown function.
        out.close();
        file.delete();
		
		return new Status(Status.StatusCode.Success);
	}

	/**
	 * Shuts the Plugin down and frees resources. This method is called in the
	 * following cases:
	 * <ul>
	 * <li>the <tt>setup</tt> method failed</li>
	 * <li>the Plugin configuration has changed</li>
	 * <li>the execution duration of the Plugin exceeded the schedule timeout</li>
	 * <li>the schedule associated with this Plugin was removed</li>
	 * </ul>
	 *
	 * <p>
	 * The Plugin methods <tt>setup</tt>, <tt>execute</tt> and
	 * <tt>teardown</tt> are called on different threads, but they are called
	 * sequentially. This means that the execution of these methods does not
	 * overlap, they are executed one after the other.
	 *
	 * <p>
	 * Examples:
	 * <ul>
	 * <li><tt>setup</tt> (failed) -&gt; <tt>teardown</tt></li>
	 * <li><tt>execute</tt> starts, configuration changes, <tt>execute</tt>
	 * ends -&gt; <tt>teardown</tt><br>
	 * on next schedule interval: <tt>setup</tt> -&gt; <tt>execute</tt> ...</li>
	 * <li><tt>execute</tt> starts, execution duration timeout,
	 * <tt>execute</tt> stops -&gt; <tt>teardown</tt></li>
	 * <li><tt>execute</tt> starts, <tt>execute</tt> ends, schedule is
	 * removed -&gt; <tt>teardown</tt></li>
	 * </ul>
	 * Failed means that either an unhandled exception is thrown or the status
	 * returned by the method contains a non-success code.
	 *
	 *
	 * <p>
	 * All by the Plugin allocated resources should be freed in this method.
	 * Examples are opened sockets or files.
	 *
	 * @see Task#setup(TaskEnvironment)
	 */
	@Override
	public void teardown(TaskEnvironment env) throws Exception {
		// TODO
		service.logout();
	}
	
	
	public Writer GetSplunkOutputStream(TaskEnvironment env)
	{
		OutputStream ostream = null;
		Writer out = null;
		try {
			Index myIndex = service.getIndexes().get(env.getConfigString("SplunkIndex"));	
			
			Args eventArgs = new Args();
			eventArgs.put("sourcetype", "_json");
			eventArgs.put("host", env.getConfigString("SplunkHost"));
			
			Socket socket = myIndex.attach(eventArgs);
			ostream = socket.getOutputStream();
			out = new OutputStreamWriter(ostream, "UTF8");
			
		} 
		catch (Exception e) {
			
			log.info("Getting Splunk output stream failed: " + e.getMessage());
			return null;
		}
		
		return out;
	}
	public boolean SendToSplunk(TaskEnvironment env, Writer ostream, String message)
	{
		
		try {
			//Date d = new Date();
			
			ostream.write(message);
			ostream.flush();
		
		} catch (Exception e) {
			
			log.info("Sending data to Splunk failed: " + e.getMessage());
			return false;
			
		}
		return true;
	}
	
	//Saves the XML to a file called DashboardXML
	public File SaveURLToFile(TaskEnvironment env) throws Exception {
		
		File file = null;
		try {
			
			String address = env.getConfigString("DTAddress");
			String dashboard = env.getConfigString("DTDashboard");
			
			dashboard = dashboard.replaceAll(" ", "%20");
			String url = "http://" + address + ":8020/rest/management/reports/create/" + dashboard + "?type=XML&format=XML+Export";
			URL u = new URL(url);
			
			String authStr = env.getConfigString("DTUser") + ":" + env.getConfigPassword("DTPassword");
			String authEncoded = Base64.encodeBytes(authStr.getBytes());
			
			
			HttpURLConnection connection = (HttpURLConnection) u.openConnection();
			connection.setRequestMethod("GET");
			connection.setDoOutput(true);
			connection.setRequestProperty("Authorization", "Basic " + authEncoded);
			
			file = new File("Dashboard.xml");
			InputStream content = (InputStream) connection.getInputStream();
			BufferedReader in   = 
	                new BufferedReader (new InputStreamReader (content));
			OutputStream out = new BufferedOutputStream(new FileOutputStream(file));

	        for (int b; (b = in.read()) != -1;) {
	            out.write(b);
	        }
	        
            
	        out.close();
	        in.close();
		}
		catch(Exception e)
		{
			log.info("Connecting to Dynatrace REST Interface failed: " + e.getMessage());
			return null;
		}
		return file;

	}
	
	//Will parse the given xml file, then send each event to Splunk
	//Currently supports the following dashlets
	//purepaths, methods, charts, database
	public void ParseXML(TaskEnvironment env, File file, Writer out){
		
		try {
			File fXmlFile = new File("Dashboard.xml");
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			Document doc = dBuilder.parse(fXmlFile);
 
			doc.getDocumentElement().normalize();
 
			//Get the system profile for later use
			String SystemProfile = "Temporary";
			NodeList nList = doc.getElementsByTagName("source");
			for (int temp = 0; temp < nList.getLength(); temp++) {
				NamedNodeMap m = nList.item(temp).getAttributes();
				for (int i = 0; i < m.getLength(); i++) {
					if(m.item(i).getNodeName() == "name")
					{
						SystemProfile = m.item(i).getNodeValue();
					}
				}
			}
			
			
//			This section contains parsing for purepaths.
//
			nList = doc.getElementsByTagName("purepath");
			int count = 0;
			
			Map<String, String> TempDict = new HashMap<String, String>();
			//Load the dictionary object with the attributes from a purepath.
			//Create a message string from that Dictionary object
			//Send that message to Splunk
			for (int temp = 0; temp < nList.getLength(); temp++) {
				TempDict.clear();
				TempDict.put("time", "0");
				TempDict.put("System Profile", SystemProfile);
				TempDict.put("DataType", "PurepathData");
				
				NamedNodeMap m = nList.item(temp).getAttributes();
				for (int i = 0; i < m.getLength(); i++) {
					if(m.item(i).getNodeName() == "start")
					{
						
						TempDict.put("time", m.item(i).getNodeValue());
					}
					else{
						TempDict.put(m.item(i).getNodeName(), m.item(i).getNodeValue());
					}
					
				}
				if(!SendToSplunk(env, out, CreateJsonMessage(TempDict))){
					out = GetSplunkOutputStream(env);
					SendToSplunk(env, out, CreateJsonMessage(TempDict));
				}
				count++;
			}
			log.info("Sent " + count + " purepath events to Splunk");
			count = 0;
			
//			This section contains parsing for Chart data. This is difficult due to the tiered structure of the XML
//			The data looks like the following
//			<data>
//		    <chartdashlet name="Memory Used" description="" showabsolutevalues="false">
//		      <measures>
//		        <measure measure="win-5ja2nu0k88c" color="#851444" 
//		        aggregation="Average" avg="5.6412950983111105E9" unit="B" 
//		        min="5.607290197333333E9" max="5.679360682666667E9" 
//		        sum="3.384777058986666E11" count="178">
			
			//The idea is to grab all measurements, then get their parent nodes for the rest of the info
			nList = doc.getElementsByTagName("measurement");
			
			for (int temp = 0; temp < nList.getLength(); temp++) {
				TempDict.clear();
				TempDict.put("System Profile", SystemProfile);
				TempDict.put("DataType", "MeasureData");
				//This parent will be the measure node. Let's pull that measure attribute out.
				Node parent = nList.item(temp).getParentNode();
				TempDict.put("measure", parent.getAttributes().getNamedItem("measure").getNodeValue());
				//Move up to the <chartdashlet... node. Get the name of the chart being used.
				TempDict.put("chart", parent.getParentNode().getParentNode().getAttributes().getNamedItem("name").getNodeValue());
				
				//Now add the attributes for the current measurement
				NamedNodeMap m = nList.item(temp).getAttributes();
				for (int i = 0; i < m.getLength(); i++) {
					if(m.item(i).getNodeName() == "timestamp")
					{
						Date time=new Date(Long.parseLong(m.item(i).getNodeValue(), 10) );
						TempDict.put("time", time.toString());
					}
					else{
						TempDict.put(m.item(i).getNodeName(), m.item(i).getNodeValue());
					}	
				}	
				
				if(!SendToSplunk(env, out, CreateJsonMessage(TempDict))){
					out = GetSplunkOutputStream(env);
					SendToSplunk(env, out, CreateJsonMessage(TempDict));
				}
				count++;
			}
			log.info("Sent " + count + " measurement events to Splunk");
			count = 0;
			
			
// These last sections are duplicates of each other. They could be merged into one, but for simplicities sake
// they are being kept apart
			//This section is parsing for Methods dashlets
			//Error
			nList = doc.getElementsByTagName("method");
			for (int temp = 0; temp < nList.getLength(); temp++) {
				TempDict.clear();
				Date d = new Date();
				TempDict.put("DataType", "MethodData");
				TempDict.put("time", d.toString());
				TempDict.put("System Profile", SystemProfile);
				
				NamedNodeMap m = nList.item(temp).getAttributes();
				for (int i = 0; i < m.getLength(); i++) {

					TempDict.put(m.item(i).getNodeName(), m.item(i).getNodeValue());
				}
				
				
				if(!SendToSplunk(env, out, CreateJsonMessage(TempDict))){
					out = GetSplunkOutputStream(env);
					SendToSplunk(env, out, CreateJsonMessage(TempDict));
				}
				count++;
			}
			log.info("Sent " + count + " method events to Splunk");
			count = 0;
			
			
			//This section is parsing for Error dashlets
			nList = doc.getElementsByTagName("record");
			for (int temp = 0; temp < nList.getLength(); temp++) {
				TempDict.clear();
				Date d = new Date();
				TempDict.put("DataType", "ErrorData");
				TempDict.put("time", d.toString());
				TempDict.put("System Profile", SystemProfile);
				
				NamedNodeMap m = nList.item(temp).getAttributes();
				for (int i = 0; i < m.getLength(); i++) {

					TempDict.put(m.item(i).getNodeName(), m.item(i).getNodeValue());
				}
				
				
				if(!SendToSplunk(env, out, CreateJsonMessage(TempDict))){
					out = GetSplunkOutputStream(env);
					SendToSplunk(env, out, CreateJsonMessage(TempDict));
				}
				count++;
			}
			log.info("Sent " + count + " error events to Splunk");
			count = 0;		
			
			//This section is parsing for Exception dashlets
			nList = doc.getElementsByTagName("exception");
			for (int temp = 0; temp < nList.getLength(); temp++) {
				TempDict.clear();
				Date d = new Date();
				TempDict.put("DataType", "ExceptionData");
				TempDict.put("time", d.toString());
				TempDict.put("System Profile", SystemProfile);
				
				NamedNodeMap m = nList.item(temp).getAttributes();
				for (int i = 0; i < m.getLength(); i++) {

					TempDict.put(m.item(i).getNodeName(), m.item(i).getNodeValue());
				}
				
				
				if(!SendToSplunk(env, out, CreateJsonMessage(TempDict))){
					out = GetSplunkOutputStream(env);
					SendToSplunk(env, out, CreateJsonMessage(TempDict));
				}
				count++;
			}
			log.info("Sent " + count + " exception events to Splunk");
			count = 0;		
			
			//This section is parsing for Tagged Web request dashlets
			nList = doc.getElementsByTagName("taggedwebrequest");
			for (int temp = 0; temp < nList.getLength(); temp++) {
				TempDict.clear();
				Date d = new Date();
				TempDict.put("DataType", "TaggedRequestData");
				TempDict.put("time", d.toString());
				TempDict.put("System Profile", SystemProfile);
				
				NamedNodeMap m = nList.item(temp).getAttributes();
				for (int i = 0; i < m.getLength(); i++) {

					TempDict.put(m.item(i).getNodeName(), m.item(i).getNodeValue());
				}
				
				
				if(!SendToSplunk(env, out, CreateJsonMessage(TempDict))){
					out = GetSplunkOutputStream(env);
					SendToSplunk(env, out, CreateJsonMessage(TempDict));
				}
				count++;
			}
			log.info("Sent " + count + " tagged web request events to Splunk");
			count = 0;
			
			//This section is parsing for Tagged Web request dashlets
			nList = doc.getElementsByTagName("webrequest");
			for (int temp = 0; temp < nList.getLength(); temp++) {
				TempDict.clear();
				Date d = new Date();
				TempDict.put("DataType", "WebRequestData");
				TempDict.put("time", d.toString());
				TempDict.put("System Profile", SystemProfile);
				
				NamedNodeMap m = nList.item(temp).getAttributes();
				for (int i = 0; i < m.getLength(); i++) {

					TempDict.put(m.item(i).getNodeName(), m.item(i).getNodeValue());
				}
				
				
				if(!SendToSplunk(env, out, CreateJsonMessage(TempDict))){
					out = GetSplunkOutputStream(env);
					SendToSplunk(env, out, CreateJsonMessage(TempDict));
				}
				count++;
			}
			log.info("Sent " + count + " web request events to Splunk");
			count = 0;
			
			
////////Done with parsing
			
		} catch (Exception e) {
			log.info("Parsing XML file failed: " + e.getMessage());
		}
		
	}
	
	
	public String CreateJsonMessage(Map<String, String> Dict){
		JSONObject json = new JSONObject();

		String TimeStr = null;
		//We need time to be the first value in the json message
		for ( String key : Dict.keySet() ) {
			if(key == "time"){
				json.put(key, Dict.get(key));
				TimeStr = "{\"" + key + "\":\"" + Dict.get(key) + "\","; 
			}
			
		}
		
		for ( String key : Dict.keySet() ) {
			if(key != "time"){
				json.put(key, Dict.get(key));
			}
		}
		if(TimeStr != null){
			return TimeStr + json.toString().replace("{", "");
		}
		
		return json.toString();
	}
}
