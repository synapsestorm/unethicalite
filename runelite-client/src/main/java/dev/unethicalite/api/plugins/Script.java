package dev.unethicalite.api.plugins;

import dev.unethicalite.api.game.Game;
import dev.unethicalite.api.script.blocking_events.BlockingEvent;
import dev.unethicalite.api.script.blocking_events.BlockingEventManager;
import dev.unethicalite.client.minimal.plugins.MinimalPluginChanged;
import dev.unethicalite.client.minimal.plugins.MinimalPluginState;
import dev.unethicalite.api.script.paint.Paint;
import lombok.Getter;
import net.runelite.api.GameState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

public abstract class Script extends LoopedPlugin
{
	protected final Logger logger;

	private boolean restart;
	private boolean paused;
	private boolean onLogin;

	public Script()
	{
		logger = LoggerFactory.getLogger(getClass());
	}

	@Inject
	@Getter
	private Paint paint;

	@Getter
	private final BlockingEventManager blockingEventManager = new BlockingEventManager();

	protected abstract int loop();

	public abstract void onStart(String... args);

	public void onStop()
	{

	}

	public void onLogin()
	{

	}

	public int outerLoop()
	{
		int loopSleep;
		if (paused)
		{
			return 1000;
		}

		if (restart)
		{
			restart = false;
			Game.getClient().getCallbacks().post(new MinimalPluginChanged(this, MinimalPluginState.RESTARTING));
			return 1000;
		}

		if (Game.getState() == GameState.LOGGED_IN && !onLogin)
		{
			onLogin = true;
			onLogin();
			return 100;
		}

		if (!blockingEventManager.getBlockingEvents().isEmpty())
		{
			for (BlockingEvent event : blockingEventManager.getBlockingEvents())
			{
				if (event.validate())
				{
					return event.loop();
				}
			}
		}

		loopSleep = loop();
		return loopSleep != 0 ? loopSleep : 1000;
	}

	public void pauseScript()
	{
		paused = !paused;
		if (!paused)
		{
			Game.getClient().getCallbacks().post(new MinimalPluginChanged(this, MinimalPluginState.STARTED));
		}
		else
		{
			Game.getClient().getCallbacks().post(new MinimalPluginChanged(this, MinimalPluginState.PAUSED));
		}
	}

	public boolean isRestart()
	{
		return restart;
	}

	public void setRestart(boolean restart)
	{
		this.restart = restart;
	}

	public boolean isPaused()
	{
		return paused;
	}

	public void setPaused(boolean paused)
	{
		this.paused = paused;
	}
}
