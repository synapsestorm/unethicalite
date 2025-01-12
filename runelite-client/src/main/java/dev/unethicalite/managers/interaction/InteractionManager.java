package dev.unethicalite.managers.interaction;

import dev.unethicalite.api.MouseHandler;
import dev.unethicalite.api.SceneEntity;
import dev.unethicalite.api.commons.Rand;
import dev.unethicalite.api.commons.Time;
import dev.unethicalite.api.events.MenuAutomated;
import dev.unethicalite.api.events.NativeMouseInput;
import dev.unethicalite.api.game.GameThread;
import dev.unethicalite.api.input.naturalmouse.NaturalMouse;
import dev.unethicalite.api.movement.Movement;
import dev.unethicalite.api.packets.Packets;
import dev.unethicalite.api.widgets.Widgets;
import dev.unethicalite.client.config.UnethicaliteConfig;
import dev.unethicalite.client.minimal.ui.MinimalUI;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.DialogOption;
import net.runelite.api.MenuAction;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.DialogProcessed;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.ContainableFrame;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.Arrays;

@Singleton
@Slf4j
public class InteractionManager
{
	private static final int MINIMAP_WIDTH = 250;
	private static final int MINIMAP_HEIGHT = 180;

	@Inject
	private NaturalMouse naturalMouse;

	@Inject
	private UnethicaliteConfig config;

	@Inject
	private Client client;

	@Inject
	InteractionManager(EventBus eventBus)
	{
		eventBus.register(this);
	}

	@Subscribe
	public void onInvokeMenuAction(MenuAutomated event)
	{
		if (config.debugMenuActions())
		{
			String debug = "O=" + event.getOption()
					+ " | T=" + event.getTarget()
					+ " | ID=" + event.getIdentifier()
					+ " | OP=" + event.getOpcode().getId()
					+ " | P0=" + event.getParam0()
					+ " | P1=" + event.getParam1()
					+ " | X=" + event.getClickX()
					+ " | Y=" + event.getClickY();

			log.debug("[Automated] {}", debug);
		}
		Point clickPoint = getClickPoint(event);
		MouseHandler mouseHandler = client.getMouseHandler();

		try
		{
			SceneEntity entity;
			switch (config.interactMethod())
			{
				case MOUSE_FORWARDING:
					if (client.getQueuedMenu() != null)
					{
						break;
					}

					client.setQueuedMenu(event);
					break;

				case MOUSE_EVENTS:
					entity = event.getEntity();
					if (entity != null && config.mouseBehavior() == MouseBehavior.CLICKBOXES)
					{
						clickPoint = entity.getClickPoint().getAwtPoint();
					}

					if (event.getOpcode() == MenuAction.WALK && clickOffScreen(clickPoint))
					{
						net.runelite.api.Point newPoint = Perspective.localToMinimap(client,
								LocalPoint.fromScene(event.getParam0(), event.getParam1()));
						if (newPoint != null)
						{
							clickPoint = newPoint.getAwtPoint();
						}
					}

					if (config.naturalMouse())
					{
						naturalMouse.moveTo(clickPoint.x, clickPoint.y);
					}

					Point finalClickPoint = clickPoint;
					GameThread.invoke(() ->
					{
						client.setPendingAutomation(event);
						setHoveredEntity(entity);

						if (!config.naturalMouse())
						{
							mouseHandler.sendMovement(finalClickPoint.x, finalClickPoint.y);
						}

						log.debug("Sending click to [{}, {}]", finalClickPoint.x, finalClickPoint.y);
						mouseHandler.sendClick(finalClickPoint.x, finalClickPoint.y, 1);
					});

					break;

				case INVOKE:
					processAction(event, clickPoint.x, clickPoint.y);
					break;

				case PACKETS:
					GameThread.invoke(() ->
					{
						try
						{
							Packets.fromAutomatedMenu(event).send();
						}
						catch (InteractionException ex)
						{
							log.error("Packet interaction failed, falling back to invoke", ex);
							processAction(event, -1, -1);
						}
					});

					break;
			}
		}
		catch (InteractionException ex)
		{
			log.error("Interaction failed: {}", ex.getMessage());
			client.setPendingAutomation(null);
		}
		finally
		{
			if (config.interactMethod() == InteractMethod.MOUSE_EVENTS)
			{
				Time.sleep(10, 20);
				mouseHandler.sendRelease();
			}
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		if (e.isAutomated() && e.getMenuAction() == MenuAction.WALK
				&& config.interactMethod() == InteractMethod.MOUSE_EVENTS)
		{
			Movement.setDestination(e.getParam0(), e.getParam1());
			e.setMenuAction(MenuAction.CANCEL);
			e.setParam0(0);
			e.setParam1(0);
		}

		if (config.debugMenuActions())
		{
			String action = "O=" + e.getMenuOption()
					+ " | T=" + e.getMenuTarget()
					+ " | ID=" + e.getId()
					+ " | OP=" + e.getMenuAction().getId()
					+ " | P0=" + e.getParam0()
					+ " | P1=" + e.getParam1()
					+ " | X=" + e.getCanvasX()
					+ " | Y=" + e.getCanvasY();
			log.debug("[Menu Action] {}", action);
		}
	}

	@Subscribe
	public void onNativeMouseInput(NativeMouseInput event)
	{
		if ((client.getQueuedMenu() == null && !config.forceForwarding())
				|| config.interactMethod() != InteractMethod.MOUSE_FORWARDING)
		{
			return;
		}

		MouseHandler mouseHandler = client.getMouseHandler();
		if (mouseHandler == null)
		{
			return;
		}

		double eventX = event.getX();
		double eventY = event.getY();

		ContainableFrame frame = MinimalUI.getFrame() != null ? MinimalUI.getFrame() : ClientUI.getFrame();

		if (frame.getBounds().contains(eventX, eventY))
		{
			return;
		}

		GraphicsDevice screen = Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
				.filter(device -> device.getDefaultConfiguration().getBounds().contains(event.getX(), event.getY()))
				.findFirst()
				.orElse(null);
		if (screen == null)
		{
			log.debug("Screen not found  to forward mouse event");
			return;
		}

		double screenWidth = screen.getDisplayMode().getWidth();
		double screenHeight = screen.getDisplayMode().getHeight();

		if (eventX < 0)
		{
			eventX = screenWidth + eventX;
		}
		else if (eventX > screenWidth)
		{
			eventX = eventX - screenWidth;
		}

		if (eventY < 0)
		{
			eventY = screenHeight + eventY;
		}
		else if (eventY > screenHeight)
		{
			eventY = eventY - screenHeight;
		}

		int finalEventX = (int) eventX;
		int finalEventY = (int) eventY;

		int x = (int) (finalEventX * ((double) client.getCanvasWidth() / screenWidth));
		int y = (int) (finalEventY * ((double) client.getCanvasHeight() / screenHeight));

		switch (event.getType())
		{
			case PRESS:
				int button = event.getButton();
				MenuAutomated queuedMenu = client.getQueuedMenu();

				if (queuedMenu == null)
				{
					if (config.forceForwarding())
					{
						mouseHandler.sendClick(x, y, button);
					}

					return;
				}

				GameThread.invoke(() ->
				{
					if (button == 1)
					{
						client.setPendingAutomation(queuedMenu);
						setHoveredEntity(queuedMenu.getEntity());
						client.setQueuedMenu(null);
					}

					log.debug(
							"Forwarding mouse press [{}] from [{}, {}, {}] to canvas [{}, {}]",
							button,
							screen.getIDstring(),
							finalEventX,
							finalEventY,
							x,
							y
					);

					mouseHandler.sendClick(x, y, button);
				});

				break;

			case RELEASE:
				mouseHandler.sendRelease();
				break;

			case MOVEMENT:
				mouseHandler.sendMovement(x, y);
				break;
		}
	}

	@Subscribe
	public void onDialogProcessed(DialogProcessed e)
	{
		if (!config.debugDialogs())
		{
			return;
		}

		DialogOption dialogOption = DialogOption.of(e.getDialogOption().getWidgetUid(), e.getDialogOption().getMenuIndex());
		if (dialogOption != null)
		{
			log.debug("Dialog processed {}", dialogOption);
		}
		else
		{
			log.debug("Unknown or unmapped dialog {}", e);
		}
	}

	private void setHoveredEntity(SceneEntity entity)
	{
		if (entity != null)
		{
			long[] entitiesAtMouse = client.getEntitiesAtMouse();
			int count = client.getEntitiesAtMouseCount();
			if (count < 1000)
			{
				entitiesAtMouse[count] = entity.getTag();
				client.setEntitiesAtMouseCount(count + 1);
			}
		}
	}

	private void processAction(MenuAutomated entry, int x, int y)
	{
		GameThread.invoke(() -> client.invokeMenuAction(entry.getOption(), entry.getTarget(), entry.getIdentifier(),
				entry.getOpcode().getId(), entry.getParam0(), entry.getParam1(), x, y));
	}

	private Point getClickPoint(MenuAutomated event)
	{
		if (config.mouseBehavior() == MouseBehavior.OFF_SCREEN)
		{
			return new Point(0, 0);
		}

		if (config.mouseBehavior() == MouseBehavior.MOUSE_POS)
		{
			return new Point(client.getMouseHandler().getCurrentX(), client.getMouseHandler().getCurrentY());
		}

		if (event.getClickX() != -1 && event.getClickY() != -1 && config.mouseBehavior() == MouseBehavior.CLICKBOXES)
		{
			Point clickPoint = new Point(event.getClickX(), event.getClickY());
			if (!clickInsideMinimap(clickPoint))
			{
				return clickPoint;
			}
		}

		Rectangle bounds = client.getCanvas().getBounds();
		Point randomPoint = new Point(Rand.nextInt(0, bounds.width), Rand.nextInt(0, bounds.height));
		if (clickInsideMinimap(randomPoint))
		{
			return getClickPoint(event);
		}

		return randomPoint;
	}

	private boolean clickInsideMinimap(Point point)
	{
		return getMinimap().contains(point);
	}

	private Rectangle getMinimap()
	{
		Widget minimap = Widgets.get(WidgetInfo.FIXED_VIEWPORT_MINIMAP_DRAW_AREA);
		if (Widgets.isVisible(minimap))
		{
			return minimap.getBounds();
		}

		Widget minimap1 = Widgets.get(WidgetInfo.RESIZABLE_MINIMAP_DRAW_AREA);
		if (Widgets.isVisible(minimap1))
		{
			return minimap1.getBounds();
		}

		Widget minimap2 = Widgets.get(WidgetInfo.RESIZABLE_MINIMAP_STONES_DRAW_AREA);
		if (Widgets.isVisible(minimap2))
		{
			return minimap2.getBounds();
		}

		Rectangle bounds = client.getCanvas().getBounds();
		return new Rectangle(bounds.width - MINIMAP_WIDTH, 0, MINIMAP_WIDTH, MINIMAP_HEIGHT);
	}

	private boolean clickOffScreen(Point point)
	{
		return point.x < 0 || point.y < 0
				|| point.x > client.getViewportWidth() || point.y > client.getViewportHeight();
	}
}
