package dev.unethicalite.api.script.paint;

import dev.unethicalite.client.devtools.EntityRenderer;
import dev.unethicalite.client.config.UnethicaliteConfig;
import dev.unethicalite.managers.InputManager;
import dev.unethicalite.managers.MinimalPluginManager;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.RenderableEntity;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Singleton
public class Paint extends Overlay
{
	private final List<RenderableEntity> overlays = new ArrayList<>();
	private boolean enabled = false;

	@Inject
	private MinimalPluginManager minimalPluginManager;

	@Inject
	private EntityRenderer entityRenderer;

	@Inject
	private UnethicaliteConfig interactionConfig;

	@Inject
	private InputManager inputManager;

	public final DefaultPaint tracker = new DefaultPaint();

	@Inject
	public Paint(OverlayManager overlayManager)
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.LOW);

		overlayManager.add(this);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Font font = g.getFont();

		if (Objects.equals("minimal", System.getenv("unethicalite.build")))
		{
			if (interactionConfig.drawMouse())
			{
				g.setFont(new Font("Tahoma", Font.BOLD, 18));
				OverlayUtil.renderTextLocation(g, new Point(inputManager.getLastClickX() - (g.getFont().getSize() / 3),
						inputManager.getLastClickY() + (g.getFont().getSize() / 3)), "X", Color.WHITE);
				OverlayUtil.renderTextLocation(g, new Point(inputManager.getLastMoveX() - (g.getFont().getSize() / 3),
						inputManager.getLastMoveY() + (g.getFont().getSize() / 3)), "X", Color.GREEN);
			}

			g.setColor(Color.WHITE);
			g.setFont(font);

			entityRenderer.render(g);
		}

		if (!enabled)
		{
			return null;
		}

		for (RenderableEntity renderableEntity : overlays)
		{
			renderableEntity.render(g);
		}

		return null;
	}

	public void submit(RenderableEntity p)
	{
		overlays.add(p);
	}

	public void clear()
	{
		overlays.clear();
		tracker.clear();
	}

	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;

		if (enabled)
		{
			submit(tracker.getTracker());
		}
	}
}
