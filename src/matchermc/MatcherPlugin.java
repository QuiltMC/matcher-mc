package matchermc;

import matcher.Plugin;
import matcher.gui.Gui;
import matcher.mapping.MappingField;

public class MatcherPlugin implements Plugin {
	@Override
	public String getName() {
		return "Minecraft features";
	}

	@Override
	public String getVersion() {
		return "1";
	}

	@Override
	public void init(int pluginApiVersion) {
		MappingBasedClassifiers.init();
		Gui.loadListeners.add(MatcherPlugin::init);
		System.out.println("mc plugin loaded");
	}

	private static void init(Gui gui) {
		McJsonProjectSetup.init(gui);
	}

	static final MappingField MC_MAPPING_FIELD = MappingField.AUX2;
}
