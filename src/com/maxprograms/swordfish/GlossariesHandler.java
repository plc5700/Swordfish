/*****************************************************************************
Copyright (c) 2007-2020 - Maxprograms,  http://www.maxprograms.com/

Permission is hereby granted, free of charge, to any person obtaining a copy of 
this software and associated documentation files (the "Software"), to compile, 
modify and use the Software in its executable form without restrictions.

Redistribution of this Software or parts of it in any form (source code or 
executable binaries) requires prior written permission from Maxprograms.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE 
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, 
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE 
SOFTWARE.
*****************************************************************************/
package com.maxprograms.swordfish;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.ParserConfigurationException;

import com.maxprograms.converters.EncodingResolver;
import com.maxprograms.languages.Language;
import com.maxprograms.swordfish.models.Memory;
import com.maxprograms.swordfish.tbx.Tbx2Tmx;
import com.maxprograms.swordfish.tm.ITmEngine;
import com.maxprograms.swordfish.tm.InternalDatabase;
import com.maxprograms.xml.Element;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xml.sax.SAXException;

public class GlossariesHandler implements HttpHandler {

	private static Logger logger = System.getLogger(GlossariesHandler.class.getName());

	private static ConcurrentHashMap<String, Memory> glossaries;
	private static ConcurrentHashMap<String, ITmEngine> openEngines;
	private static ConcurrentHashMap<String, String[]> openTasks;
	private static boolean firstRun = true;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			String request;
			URI uri = exchange.getRequestURI();
			try (InputStream is = exchange.getRequestBody()) {
				request = TmsServer.readRequestBody(is);
			}
			JSONObject response = processRequest(uri.toString(), request);
			byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseHeaders().add("content-type", "application/json; charset=utf-8");
			try (ByteArrayInputStream stream = new ByteArrayInputStream(bytes)) {
				try (OutputStream os = exchange.getResponseBody()) {
					byte[] array = new byte[2048];
					int read;
					while ((read = stream.read(array)) != -1) {
						os.write(array, 0, read);
					}
				}
			}
		} catch (IOException e) {
			logger.log(Level.ERROR, "Error processing glossary " + exchange.getRequestURI().toString(), e);
		}

	}

	private JSONObject processRequest(String url, String request) {
		if (TmsServer.isDebug()) {
			logger.log(Level.INFO, url);
		}
		JSONObject response = new JSONObject();
		try {
			if ("/glossaries/create".equals(url)) {
				response = createGlossary(request);
			} else if ("/glossaries/list".equals(url)) {
				response = listGlossaries(request);
			} else if ("/glossaries/delete".equals(url)) {
				response = deleteGlossary(request);
			} else if ("/glossaries/export".equals(url)) {
				response = exportGlossary(request);
			} else if ("/glossaries/import".equals(url)) {
				response = importGlossary(request);
			} else if ("/glossaries/status".equals(url)) {
				response = getProcessStatus(request);
			} else if ("/glossaries/search".equals(url)) {
				response = searchTerm(request);
			} else if ("/glossaries/addTerm".equals(url)) {
				response = addTerm(request);
			} else {
				response.put(Constants.REASON, "Unknown request");
			}

			if (!response.has(Constants.REASON)) {
				response.put(Constants.STATUS, Constants.SUCCESS);
			} else {
				response.put(Constants.STATUS, Constants.ERROR);
			}
		} catch (Exception j) {
			logger.log(Level.ERROR, j.getMessage(), j);
			response.put(Constants.STATUS, Constants.ERROR);
			response.put(Constants.REASON, j.getMessage());
		}
		return response;
	}

	private static JSONObject getProcessStatus(String request) {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		if (!json.has("process")) {
			result.put(Constants.REASON, "Missing 'process' parameter");
			return result;
		}
		String process = json.getString("process");
		if (openTasks == null) {
			openTasks = new ConcurrentHashMap<>();
		}
		if (openTasks.containsKey(process)) {
			String[] status = openTasks.get(process);
			result.put("result", status[0]);
			if (Constants.COMPLETED.equals(status[0]) && status.length > 1) {
				result.put("data", new JSONObject(status[1]));
			}
			if (Constants.ERROR.equals(status[0])) {
				result.put(Constants.REASON, status[1]);
			}
		} else {
			result.put("result", Constants.ERROR);
			result.put(Constants.REASON, "No such process: " + process);
		}
		return result;
	}

	private static JSONObject createGlossary(String request) throws IOException, SQLException {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		if (!json.has("id")) {
			json.put("id", "" + System.currentTimeMillis());
		}
		if (!json.has("creationDate")) {
			json.put("creationDate", System.currentTimeMillis());
		}
		Memory mem = new Memory(json);
		InternalDatabase engine = new InternalDatabase(mem.getId(), getWorkFolder());
		engine.close();
		if (glossaries == null) {
			loadGlossariesList();
		}
		glossaries.put(mem.getId(), mem);
		ServicesHandler.addClient(json.getString("client"));
		ServicesHandler.addSubject(json.getString("subject"));
		ServicesHandler.addProject(json.getString("project"));
		saveGlossariesList();
		return result;
	}

	private static void loadGlossariesList() throws IOException {
		glossaries = new ConcurrentHashMap<>();
		File home = new File(getWorkFolder());
		File list = new File(home, "glossaries.json");
		if (!list.exists()) {
			return;
		}
		StringBuffer buffer = new StringBuffer();
		try (FileReader input = new FileReader(list)) {
			try (BufferedReader reader = new BufferedReader(input)) {
				String line;
				while ((line = reader.readLine()) != null) {
					buffer.append(line);
				}
			}
		}
		JSONObject json = new JSONObject(buffer.toString());
		Set<String> keys = json.keySet();
		Iterator<String> it = keys.iterator();
		while (it.hasNext()) {
			String key = it.next();
			JSONObject obj = json.getJSONObject(key);
			glossaries.put(key, new Memory(obj));
		}
		if (firstRun) {
			firstRun = false;
			new Thread(() -> {
				try {
					File[] filesList = home.listFiles();
					for (int i = 0; i < filesList.length; i++) {
						if (filesList[i].isDirectory() && !glossaries.containsKey(filesList[i].getName())) {
							TmsServer.deleteFolder(filesList[i].getAbsolutePath());
						}
					}
				} catch (IOException e) {
					logger.log(Level.WARNING, "Error deleting folder", e);
				}
			}).start();
		}
	}

	private static void saveGlossariesList() throws IOException {
		JSONObject json = new JSONObject();
		Set<String> keys = glossaries.keySet();
		Iterator<String> it = keys.iterator();
		while (it.hasNext()) {
			String key = it.next();
			Memory m = glossaries.get(key);
			json.put(key, m.toJSON());
		}
		File home = new File(getWorkFolder());
		File list = new File(home, "glossaries.json");
		try (FileOutputStream out = new FileOutputStream(list)) {
			out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
		}
	}

	private static JSONObject listGlossaries(String request) throws IOException {
		JSONObject result = new JSONObject();
		JSONArray array = new JSONArray();
		result.put("glossaries", array);
		if (glossaries == null) {
			loadGlossariesList();
		}
		Vector<Memory> vector = new Vector<>();
		vector.addAll(glossaries.values());
		Collections.sort(vector);
		Iterator<Memory> it = vector.iterator();
		while (it.hasNext()) {
			Memory m = it.next();
			array.put(m.toJSON());
		}
		return result;
	}

	private static JSONObject deleteGlossary(String request) {
		JSONObject result = new JSONObject();
		final JSONObject json = new JSONObject(request);

		if (json.has("glossaries")) {
			final String process = "" + System.currentTimeMillis();
			result.put("process", process);
			if (openTasks == null) {
				openTasks = new ConcurrentHashMap<>();
			}
			openTasks.put(process, new String[] { Constants.PROCESSING });
			new Thread(() -> {
				try {
					JSONArray array = json.getJSONArray("glossaries");
					for (int i = 0; i < array.length(); i++) {
						Memory mem = glossaries.get(array.getString(i));
						if (openEngines != null && openEngines.contains(mem.getId())) {
							ITmEngine engine = openEngines.get(mem.getId());
							engine.close();
							openEngines.remove(mem.getId());
						}
						try {
							File wfolder = new File(getWorkFolder(), mem.getId());
							TmsServer.deleteFolder(wfolder.getAbsolutePath());
						} catch (IOException ioe) {
							logger.log(Level.WARNING, "Folder '" + mem.getId() + "' will be deleted on next start");
						}
						glossaries.remove(mem.getId());
					}
					saveGlossariesList();
					openTasks.put(process, new String[] { Constants.COMPLETED });
				} catch (IOException | SQLException e) {
					logger.log(Level.ERROR, e.getMessage(), e);
					openTasks.put(process, new String[] { Constants.ERROR, e.getMessage() });
				}
			}).start();
		} else {
			result.put(Constants.REASON, "Missing 'glossaries' parameter");
		}
		return result;
	}

	private static JSONObject exportGlossary(String request) {
		JSONObject result = new JSONObject();
		final JSONObject json = new JSONObject(request);
		if (!json.has("glossary")) {
			result.put(Constants.REASON, "Missing 'glossary' parameter");
			return result;
		}
		if (!json.has("file")) {
			result.put(Constants.REASON, "Missing 'file' parameter");
			return result;
		}
		if (!json.has("srcLang")) {
			json.put("srcLang", "*all*");
		}
		final String process = "" + System.currentTimeMillis();
		if (openTasks == null) {
			openTasks = new ConcurrentHashMap<>();
		}
		openTasks.put(process, new String[] { Constants.PROCESSING });
		new Thread(() -> {
			try {
				if (glossaries == null) {
					loadGlossariesList();
				}
				Memory mem = glossaries.get(json.getString("glossary"));
				if (openEngines == null) {
					openEngines = new ConcurrentHashMap<>();
				}
				boolean needsClosing = false;
				if (!openEngines.containsKey(mem.getId())) {
					needsClosing = true;
					openGlossary(mem.getId());
				}
				ITmEngine engine = openEngines.get(mem.getId());
				File tmx = new File(json.getString("file"));
				Set<String> langSet = Collections.synchronizedSortedSet(new TreeSet<>());
				if (json.has("languages")) {
					JSONArray langs = json.getJSONArray("languages");
					for (int i = 0; i < langs.length(); i++) {
						langSet.add(langs.getString(i));
					}
				}
				engine.exportMemory(tmx.getAbsolutePath(), langSet, json.getString("srcLang"));
				if (needsClosing) {
					closeGlossary(mem.getId());
				}
				openTasks.put(process, new String[] { Constants.COMPLETED });
			} catch (IOException | JSONException | SAXException | ParserConfigurationException | SQLException e) {
				logger.log(Level.ERROR, e.getMessage(), e);
				openTasks.put(process, new String[] { Constants.ERROR, e.getMessage() });
			}
		}).start();
		result.put("process", process);
		return result;
	}

	public static void openGlossary(String id) throws IOException, SQLException {
		if (glossaries == null) {
			loadGlossariesList();
		}
		if (openEngines == null) {
			openEngines = new ConcurrentHashMap<>();
		}
		if (openEngines.contains(id)) {
			return;
		}
		openEngines.put(id, new InternalDatabase(id, getWorkFolder()));
	}

	public static ITmEngine getEngine(String id) throws SQLException, IOException {
		if (glossaries == null) {
			loadGlossariesList();
		}
		if (openEngines == null) {
			openEngines = new ConcurrentHashMap<>();
			openEngines.put(id, new InternalDatabase(id, getWorkFolder()));
		}
        return openEngines.get(id);
	}
	
	public static boolean isOpen(String id) {
		if (glossaries == null) {
			return false;
		}
		if (openEngines == null) {
			return false;
		}
		return openEngines.containsKey(id);
	}
	public static void closeGlossary(String id) {
		if (openEngines == null) {
			openEngines = new ConcurrentHashMap<>();
			logger.log(Level.WARNING, "Closing glossary when 'openEngines' is null");
		}
		if (!openEngines.contains(id)) {
			return;
		}
		try {
			openEngines.get(id).close();
		} catch (IOException | SQLException e) {
			logger.log(Level.WARNING, e);
		}
		openEngines.remove(id);
	}

	private JSONObject importGlossary(String request) {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		if (!json.has("glossary")) {
			result.put(Constants.REASON, "Missing 'glossary' parameter");
			return result;
		}
		String id = json.getString("glossary");

		if (!json.has("file")) {
			result.put(Constants.REASON, "Missing 'file' parameter");
			return result;
		}
		File glossFile = new File(json.getString("file"));
		if (!glossFile.exists()) {
			result.put(Constants.REASON, "Glossary file does not exist");
			return result;
		}

		final String process = "" + System.currentTimeMillis();
		if (openTasks == null) {
			openTasks = new ConcurrentHashMap<>();
		}
		openTasks.put(process, new String[] { Constants.PROCESSING });
		new Thread(() -> {
			try {
				if (openEngines == null) {
					openEngines = new ConcurrentHashMap<>();
				}
				boolean needsClosing = false;
				if (!openEngines.containsKey(id)) {
					openGlossary(id);
					needsClosing = true;
				}
				File tempFile = null;
				String tmxFile = glossFile.getAbsolutePath();
				if (isTBX(glossFile)) {
					tempFile = File.createTempFile("gloss", ".tmx");
					Tbx2Tmx.convert(tmxFile, tempFile.getAbsolutePath());
					tmxFile = tempFile.getAbsolutePath();
				}
				ITmEngine engine = openEngines.get(id);
				String project = json.has("project") ? json.getString("project") : "";
				String client = json.has("client") ? json.getString("client") : "";
				String subject = json.has("subject") ? json.getString("subject") : "";
				try {
					int imported = engine.storeTMX(tmxFile, project, client, subject);
					logger.log(Level.INFO, "Imported " + imported);
					openTasks.put(process, new String[] { Constants.COMPLETED });
				} catch (Exception e) {
					openTasks.put(process, new String[] { Constants.ERROR, e.getMessage() });
					logger.log(Level.ERROR, e.getMessage(), e);
				}
				if (needsClosing) {
					closeGlossary(id);
				}
				if (tempFile != null) {
					Files.delete(tempFile.toPath());
				}
			} catch (IOException | SQLException | SAXException | ParserConfigurationException | URISyntaxException e) {
				logger.log(Level.ERROR, e.getMessage(), e);
				openTasks.put(process, new String[] { Constants.ERROR, e.getMessage() });
			}
		}).start();
		result.put("process", process);
		return result;
	}

	public static JSONArray getGlossaries() throws IOException {
		JSONArray result = new JSONArray();
		if (glossaries == null) {
			loadGlossariesList();
		}
		Vector<Memory> vector = new Vector<>();
		vector.addAll(glossaries.values());
		Collections.sort(vector);
		Iterator<Memory> it = vector.iterator();
		while (it.hasNext()) {
			Memory m = it.next();
			JSONArray array = new JSONArray();
			array.put(m.getId());
			array.put(m.getName());
			result.put(array);
		}
		return result;
	}

	public static String getWorkFolder() throws IOException {
		File home = TmsServer.getWorkFolder();
		File workFolder = new File(home, "glossaries");
		if (!workFolder.exists()) {
			Files.createDirectories(workFolder.toPath());
		}
		return workFolder.getAbsolutePath();
	}

	private boolean isTBX(File file) throws IOException {
		byte[] array = new byte[40960];
		try (FileInputStream input = new FileInputStream(file)) {
			if (input.read(array) == -1) {
				throw new IOException("Premature end of file");
			}
		}
		String string = "";
		Charset bom = EncodingResolver.getBOM(file.getAbsolutePath());
		if (bom != null) {
			byte[] efbbbf = { -17, -69, -65 }; // UTF-8
			String utf8 = new String(efbbbf);
			string = new String(array, bom);
			if (string.startsWith("\uFFFE")) {
				string = string.substring("\uFFFE".length());
			} else if (string.startsWith("\uFEFF")) {
				string = string.substring("\uFEFF".length());
			} else if (string.startsWith(utf8)) {
				string = string.substring(utf8.length());
			}
		} else {
			string = new String(array);
		}
		return string.indexOf("<tmx ") == -1;
	}

	private JSONObject addTerm(String request) {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		if (!json.has("glossary")) {
			result.put(Constants.REASON, "Missing 'glossary' parameter");
			return result;
		}
		try {
			String glossary = json.getString("glossary");
			if (openEngines == null) {
				openEngines = new ConcurrentHashMap<>();
			}
			boolean needsClosing = false;
			if (!openEngines.containsKey(glossary)) {
				openGlossary(glossary);
				needsClosing = true;
			}
			Element tu = new Element("tu");
			Element srcTuv = new Element("tuv");
			srcTuv.setAttribute("xml:lang", json.getString("srcLang"));
			tu.addContent(srcTuv);
			Element srcSeg = new Element("seg");
			srcSeg.setText(json.getString("sourceTerm"));
			srcTuv.addContent(srcSeg);
			Element tgtTuv = new Element("tuv");
			tgtTuv.setAttribute("xml:lang", json.getString("tgtLang"));
			tu.addContent(tgtTuv);
			Element tgtSeg = new Element("seg");
			tgtSeg.setText(json.getString("targetTerm"));
			tgtTuv.addContent(tgtSeg);
			openEngines.get(glossary).storeTu(tu);
			openEngines.get(glossary).commit();
			if (needsClosing) {
				closeGlossary(glossary);
			}
		} catch (IOException | SQLException e) {
			logger.log(Level.ERROR, e);
			result.put("result", Constants.ERROR);
			result.put(Constants.REASON, e.getMessage());
		}
		return result;
	}

	public static JSONObject searchTerm(String request) {
		JSONObject result = new JSONObject();
		JSONObject json = new JSONObject(request);
		if (!json.has("glossary")) {
			result.put(Constants.REASON, "Missing 'glossary' parameter");
			return result;
		}
		String searchStr = json.getString("searchStr");
		String srcLang = json.getString("srcLang");
		int similarity = json.getInt("similarity");
		boolean caseSensitive = json.getBoolean("caseSensitive");
		String glossary = json.getString("glossary");
		try {
			List<Element> matches = new Vector<>();
			if (openEngines == null) {
				openEngines = new ConcurrentHashMap<>();
			}
			boolean needsClosing = false;
			if (!openEngines.containsKey(glossary)) {
				openGlossary(glossary);
				needsClosing = true;
			}
			matches.addAll(openEngines.get(glossary).searchAll(searchStr, srcLang, similarity, caseSensitive));
			if (needsClosing) {
				closeGlossary(glossary);
			}
			result.put("count", matches.size());
			result.put("html", generateHTML(matches));
		} catch (IOException | SAXException | ParserConfigurationException | SQLException e) {
			logger.log(Level.ERROR, e);
			result.put("result", Constants.ERROR);
			result.put(Constants.REASON, e.getMessage());
		}
		return result;
	}

	private static String generateHTML(List<Element> matches) throws IOException {
		StringBuilder builder = new StringBuilder();
		builder.append("<table class='stripes'><tr>");
		List<Language> languages = MemoriesHandler.getLanguages(matches);
		Iterator<Language> st = languages.iterator();
		while (st.hasNext()) {
			builder.append("<th>");
			builder.append(st.next().getDescription());
			builder.append("</th>");
		}
		builder.append("</tr>");
		for (int i = 0; i < matches.size(); i++) {
			builder.append("<tr>");
			builder.append(parseTU(matches.get(i), languages));
			builder.append("</tr>");
		}
		builder.append("</table>");
		return builder.toString();
	}

	private static String parseTU(Element element, List<Language> languages) {
		StringBuilder builder = new StringBuilder();
		Map<String, Element> map = new Hashtable<>();
		List<Element> tuvs = element.getChildren("tuv");
		Iterator<Element> it = tuvs.iterator();
		while (it.hasNext()) {
			Element tuv = it.next();
			map.put(tuv.getAttributeValue("xml:lang"), tuv);
		}
		for (int i = 0; i < languages.size(); i++) {
			Language lang = languages.get(i);
			builder.append("<td ");
			if (lang.isBiDi()) {
				builder.append("dir='rtl'");
			}
			builder.append(" lang='");
			builder.append(lang.getCode());
			builder.append("'>");
			if (map.containsKey(lang.getCode())) {
				Element seg = map.get(lang.getCode()).getChild("seg");
				builder.append(MemoriesHandler.pureText(seg));
			} else {
				builder.append("&nbsp;");
			}
			builder.append("</td>");
		}
		return builder.toString();
	}
}
