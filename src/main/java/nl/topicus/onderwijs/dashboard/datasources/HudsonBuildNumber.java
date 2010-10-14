package nl.topicus.onderwijs.dashboard.datasources;

import nl.topicus.onderwijs.dashboard.modules.DataSource;
import nl.topicus.onderwijs.dashboard.modules.DataSourceSettings;

@DataSourceSettings(label = "Buildnumber", htmlClass = "number", type = Integer.class)
public interface HudsonBuildNumber extends DataSource<Integer> {
}
