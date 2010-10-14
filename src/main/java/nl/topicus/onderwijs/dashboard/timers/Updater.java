package nl.topicus.onderwijs.dashboard.timers;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import nl.topicus.onderwijs.dashboard.modules.Repository;
import nl.topicus.onderwijs.dashboard.modules.hudson.HudsonUpdateTask;
import nl.topicus.onderwijs.dashboard.modules.topicus.TopicusProjectsUpdateTask;
import nl.topicus.onderwijs.dashboard.web.WicketApplication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Updater {
	private static final Logger log = LoggerFactory.getLogger(Updater.class);
	private ScheduledExecutorService timer;
	private WicketApplication application;
	private ScheduledFuture<?> scheduledFuture;

	public Updater(WicketApplication application, Repository repository) {
		this.application = application;

		timer = Executors.newScheduledThreadPool(1);

		scheduledFuture = timer.scheduleWithFixedDelay(new TimerTask(), 0, 1,
				TimeUnit.MINUTES);
	}

	public void stop() {
		scheduledFuture.cancel(true);
		timer.shutdownNow();
		try {
			boolean terminated = timer.awaitTermination(10, TimeUnit.SECONDS);
			if (!terminated) {
				log.error("Failed to terminate the timer within 10 seconds");
			}
		} catch (InterruptedException e) {
			log.info("Terminating the timer was interrupted", e);
		}
	}

	class TimerTask implements Runnable {
		List<Runnable> tasks = Arrays.asList(
				//
				new TopicusProjectsUpdateTask(application),
				new HudsonUpdateTask(application));

		@Override
		public void run() {
			for (Runnable task : tasks) {
				try {
					if (application.isUpdaterEnabled()
							&& !application.isShuttingDown()) {
						task.run();
					}
				} catch (Exception e) {
					log.error("Uncaught exception during update task "
							+ task.getClass().getSimpleName() + ": "
							+ e.getClass().getSimpleName() + ": "
							+ e.getMessage());
				}
			}
		}
	}
}
