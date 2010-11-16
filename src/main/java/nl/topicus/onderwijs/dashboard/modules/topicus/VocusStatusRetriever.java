package nl.topicus.onderwijs.dashboard.modules.topicus;

import static nl.topicus.onderwijs.dashboard.modules.topicus.RetrieverUtils.getStatuspage;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import net.htmlparser.jericho.Element;
import net.htmlparser.jericho.HTMLElementName;
import net.htmlparser.jericho.Source;
import nl.topicus.onderwijs.dashboard.datasources.ApplicationVersion;
import nl.topicus.onderwijs.dashboard.datasources.AverageRequestTime;
import nl.topicus.onderwijs.dashboard.datasources.NumberOfServers;
import nl.topicus.onderwijs.dashboard.datasources.NumberOfServersOffline;
import nl.topicus.onderwijs.dashboard.datasources.NumberOfUsers;
import nl.topicus.onderwijs.dashboard.datasources.RequestsPerMinute;
import nl.topicus.onderwijs.dashboard.datasources.ServerAlerts;
import nl.topicus.onderwijs.dashboard.datasources.ServerStatus;
import nl.topicus.onderwijs.dashboard.datasources.Uptime;
import nl.topicus.onderwijs.dashboard.datatypes.Alert;
import nl.topicus.onderwijs.dashboard.datatypes.DotColor;
import nl.topicus.onderwijs.dashboard.keys.Key;
import nl.topicus.onderwijs.dashboard.modules.Repository;
import nl.topicus.onderwijs.dashboard.modules.Settings;

import org.apache.wicket.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class VocusStatusRetriever implements Retriever,
		TopicusApplicationStatusProvider {
	private static final Logger log = LoggerFactory
			.getLogger(VocusStatusRetriever.class);

	private Map<Key, TopicusApplicationStatus> statusses = new HashMap<Key, TopicusApplicationStatus>();

	private Map<String, Alert> oldAlerts = new HashMap<String, Alert>();

	public VocusStatusRetriever() {
	}

	@Override
	public TopicusApplicationStatus getStatus(Key project) {
		return statusses.get(project);
	}

	@Override
	public void onConfigure(Repository repository) {
		Map<Key, Map<String, ?>> serviceSettings = Settings.get()
				.getServiceSettings(VocusStatusRetriever.class);
		for (Key project : serviceSettings.keySet()) {
			statusses.put(project, new TopicusApplicationStatus());
			repository.addDataSource(project, NumberOfUsers.class,
					new NumberOfUsersImpl(project, this));
			repository.addDataSource(project, AverageRequestTime.class,
					new AverageRequestTimeImpl(project, this));
			repository.addDataSource(project, RequestsPerMinute.class,
					new RequestsPerMinuteImpl(project, this));
			repository.addDataSource(project, NumberOfServers.class,
					new NumberOfServersImpl(project, this));
			repository.addDataSource(project, NumberOfServersOffline.class,
					new NumberOfServersOfflineImpl(project, this));
			repository.addDataSource(project, Uptime.class, new UptimeImpl(
					project, this));
			repository.addDataSource(project, ApplicationVersion.class,
					new ApplicationVersionImpl(project, this));
			repository.addDataSource(project, ServerStatus.class,
					new ServerStatusImpl(project, this));
			repository.addDataSource(project, ServerAlerts.class,
					new AlertsImpl(project, this));
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void refreshData() {
		HashMap<Key, TopicusApplicationStatus> newStatusses = new HashMap<Key, TopicusApplicationStatus>();
		Map<Key, Map<String, ?>> serviceSettings = Settings.get()
				.getServiceSettings(VocusStatusRetriever.class);

		for (Map.Entry<Key, Map<String, ?>> configEntry : serviceSettings
				.entrySet()) {
			Key project = configEntry.getKey();
			Map<String, String> urls = (Map<String, String>) configEntry
					.getValue().get("urls");
			TopicusApplicationStatus status = getProjectData(project, urls);
			newStatusses.put(project, status);
		}
		statusses = newStatusses;
	}

	private TopicusApplicationStatus getProjectData(Key project,
			Map<String, String> urls) {
		TopicusApplicationStatus status = new TopicusApplicationStatus();
		if (urls == null || urls.isEmpty()) {
			return status;
		}
		List<Alert> alerts = new ArrayList<Alert>();
		for (Entry<String, String> statusUrlEntry : urls.entrySet()) {
			String statusCode = statusUrlEntry.getKey();
			String statusUrl = statusUrlEntry.getValue();
			TopicusServerStatus server = new TopicusServerStatus(statusCode,
					statusUrl);
			status.addServer(server);
			Alert oldAlert = oldAlerts.get(statusUrl);
			try {
				StatusPageResponse statuspage = getStatuspage(statusUrl);
				if (statuspage.isOffline()) {
					server.setServerStatus(DotColor.RED);
					Alert alert = new Alert(oldAlert, DotColor.RED, project,
							"Server " + statusCode + " offline");
					oldAlerts.put(statusUrl, alert);
					alerts.add(alert);
					continue;
				}

				String page = statuspage.getPageContent();
				Source source = new Source(page);

				source.fullSequentialParse();

				List<Element> tableHeaders = source
						.getAllElements(HTMLElementName.TH);
				for (Element tableHeader : tableHeaders) {
					String contents = tableHeader.getContent().toString();
					if ("Applicatie".equals(contents)) {
						fetchApplicationVersion(server, tableHeader);
					} else if ("Sessions/Requests".equals(contents)) {
						fetchSessionAndRequestData(server, tableHeader);
					} else if ("Sessies/Requests".equals(contents)) {
						fetchSessionAndRequestData(server, tableHeader);
					}
				}

				List<Element> tdHeaders = source
						.getAllElements(HTMLElementName.TD);
				for (Element td : tdHeaders) {
					String contents = td.getContent().toString();
					if ("Starttijd".equals(contents)) {
						getStartTime(server, td);
					}
				}
				server.setServerStatus(DotColor.GREEN);
				oldAlerts.put(statusUrl, null);
			} catch (Exception e) {
				server.setServerStatus(DotColor.YELLOW);
				Alert alert = new Alert(oldAlert, DotColor.YELLOW, project, e
						.getMessage());
				oldAlerts.put(statusUrl, alert);
				alerts.add(alert);
				log.warn("Could not retrieve status for '" + statusUrl + "': "
						+ e.getClass().getSimpleName() + " - "
						+ e.getLocalizedMessage());
			}
		}
		status.setAlerts(alerts);
		log.info("Application status: {}->{}", project, status);
		return status;
	}

	private void fetchSessionAndRequestData(TopicusServerStatus server,
			Element tableHeader) {
		List<Element> tableRows = tableHeader.getParentElement()
				.getParentElement().getAllElements(HTMLElementName.TR);
		for (Element curRow : tableRows) {
			Element nameColumn = curRow.getFirstElement("class", "name_column",
					true);
			if (nameColumn == null)
				continue;
			String name = nameColumn.getTextExtractor().toString();
			String value = curRow
					.getFirstElement("class", "value_column", true)
					.getTextExtractor().toString();
			if ("Live sessies".equals(name) || "Live Sessions".equals(name)) {
				try {
					server.setNumberOfUsers(Integer.parseInt(value));
				} catch (NumberFormatException e) {
					log.error("Cannot parse number of users: " + value);
				}
			} else if ("Gem. request duur".equals(name)) {
				try {
					int space = value.indexOf(' ');
					if (space > -1) {
						value = value.substring(0, space);
					}
					server.setAverageRequestDuration(Integer.parseInt(value));
				} catch (NumberFormatException e) {
					log.error("Cannot parse avg request duration: " + value);
				}
			} else if ("Requests per minuut".equals(name)) {
				try {
					server.setRequestsPerMinute(Integer.parseInt(value));
				} catch (NumberFormatException e) {
					log.error("Cannot parse req per minute: " + value);
				}
			}
		}
	}

	private void fetchApplicationVersion(TopicusServerStatus server,
			Element tableHeader) {
		Element versieCell = tableHeader.getParentElement().getParentElement()
				.getContent().getFirstElement("class", "value_column", true);
		server
				.setVersion(versieCell.getContent().getTextExtractor()
						.toString());
	}

	/*
	 * <tr><td class="name_column">Starttijd</td><td
	 * class="value_column"><span>10-10-2010 03:51:22</span></td></tr>
	 */
	private void getStartTime(TopicusServerStatus server, Element td) {
		Element starttijdCell = td.getParentElement().getFirstElement("class",
				"value_column", true);
		String starttijdText = starttijdCell.getTextExtractor().toString();
		SimpleDateFormat sdf1 = new SimpleDateFormat("dd-MM-yyyy hh:mm:ss");
		SimpleDateFormat sdf2 = new SimpleDateFormat("dd-MM-yyyy hh:mm");
		Date starttime = null;
		try {
			starttime = sdf1.parse(starttijdText);
		} catch (ParseException e) {
			try {
				starttime = sdf2.parse(starttijdText);
			} catch (ParseException e2) {
				log.error("Unable to parse starttime " + starttijdText
						+ " according to format dd-MM-yyyy hh:mm:ss and"
						+ " dd-MM-yyyy hh:mm", e);
			}
		}
		if (starttime != null) {
			Date now = new Date();
			server.setUptime(Duration.milliseconds(
					now.getTime() - starttime.getTime()).getMilliseconds());
		}
	}
}
