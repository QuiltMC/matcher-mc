package matchermc;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.FileChooser.ExtensionFilter;
import matcher.Matcher;
import matcher.config.Config;
import matcher.config.ProjectConfig;
import matcher.gui.Gui;
import matcher.gui.Gui.SelectedFile;
import matcher.type.InputFile;

class McJsonProjectSetup {
	public static void init(Gui gui) {
		List<MenuItem> items = gui.getMenu().getFileMenu().getItems();
		int pos = items.size();

		for (int i = 0; i < items.size(); i++) {
			MenuItem item = items.get(i);

			if (item instanceof SeparatorMenuItem) {
				pos = i;
				break;
			}
		}

		MenuItem menuItem = new MenuItem("New MC project");
		menuItem.setOnAction(event -> newMcProject(gui));

		items.add(pos, menuItem);
		items.add(pos, new SeparatorMenuItem());
	}

	private static void newMcProject(Gui gui) {
		List<ExtensionFilter> extFilters = Collections.singletonList(new ExtensionFilter("jSON", "*.json"));
		SelectedFile jsonA = Gui.requestFile("Select version JSON A", gui.getScene().getWindow(), extFilters, true);
		if (jsonA == null) return;
		SelectedFile jsonB = Gui.requestFile("Select version JSON B", gui.getScene().getWindow(), extFilters, true);
		if (jsonB == null) return;

		Set<InputFile> inputsA = new LinkedHashSet<>();
		Set<InputFile> inputsB = new LinkedHashSet<>();
		Set<InputFile> libsA = new LinkedHashSet<>();
		Set<InputFile> libsB = new LinkedHashSet<>();

		getLibs(jsonA.path, inputsA, libsA);
		getLibs(jsonB.path, inputsB, libsB);

		Set<InputFile> libsCommon = new LinkedHashSet<>(libsA);
		libsCommon.retainAll(libsB);
		libsA.removeAll(libsCommon);
		libsB.removeAll(libsCommon);

		System.out.println("inputs A: "+inputsA);
		System.out.println("inputs B: "+inputsB);
		System.out.println("common libs: "+libsCommon);
		System.out.println("libs A: "+libsA);
		System.out.println("libs B: "+libsB);

		gui.getMenu().getFileMenu().requestProjectLoadSettings(loadSettings -> {
			List<Path> inputDirs = loadSettings.paths;

			try {
				List<Path> pathsA = Matcher.resolvePaths(inputDirs, inputsA);
				List<Path> pathsB = Matcher.resolvePaths(inputDirs, inputsB);
				List<Path> classPathA = Matcher.resolvePaths(inputDirs, libsA);
				List<Path> classPathB = Matcher.resolvePaths(inputDirs, libsB);
				List<Path> sharedClassPath = Matcher.resolvePaths(inputDirs, libsCommon);

				ProjectConfig prevConfig = Config.getProjectConfig();
				ProjectConfig config = new ProjectConfig(pathsA, pathsB, classPathA, classPathB, sharedClassPath,
						prevConfig.hasInputsBeforeClassPath(),
						nonObfuscatedClassPattern, nonObfuscatedClassPattern,
						prevConfig.getNonObfuscatedMemberPatternA(), prevConfig.getNonObfuscatedMemberPatternB());

				gui.getMenu().getFileMenu().newProject(config);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	private static void getLibs(Path jsonFile, Set<InputFile> inputsOut, Set<InputFile> libsOut) {
		JsonElement jsonElem;

		try (Reader reader = Files.newBufferedReader(jsonFile)) {
			jsonElem = new JsonParser().parse(reader);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		JsonObject json = jsonElem.getAsJsonObject();

		String id = json.get("id").getAsString();
		inputsOut.add(new InputFile(id+".jar"));
		inputsOut.add(new InputFile("minecraft_server."+id+".jar"));

		JsonElement libs = json.get("libraries");

		if (libs != null) {
			for (JsonElement libElem : libs.getAsJsonArray()) {
				JsonObject lib = libElem.getAsJsonObject();
				JsonElement rules = lib.get("rules");

				if (rules != null) {
					boolean allowed = false;

					for (JsonElement ruleElem : rules.getAsJsonArray()) {
						JsonObject rule = ruleElem.getAsJsonObject();

						JsonElement osElem = rule.get("os");

						if (osElem != null && !osElem.getAsJsonObject().get("name").getAsString().equals(osName)) {
							continue;
						}

						allowed = rule.get("action").getAsString().equals("allow");
						if (!allowed) break;
					}

					if (!allowed) continue;
				}

				String suffix = "";
				JsonElement natives = lib.get("natives");

				if (natives != null) {
					JsonElement nativeEntry = natives.getAsJsonObject().get(osName);

					if (nativeEntry != null) {
						suffix = "-".concat(nativeEntry.getAsString());
					}
				}

				String name = lib.get("name").getAsString();
				String[] nameParts = name.split(":");
				if (nameParts.length != 3) throw new RuntimeException("invalid lib name: "+name);

				libsOut.add(new InputFile(String.format("%s-%s%s.jar", nameParts[1], nameParts[2], suffix)));
			}
		}
	}

	private static final String osName = "linux";
	private static final String nonObfuscatedClassPattern = ".+/.+"; // anything not in the root package
}
