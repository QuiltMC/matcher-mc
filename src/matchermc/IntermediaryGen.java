package matchermc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.MenuItem;
import javafx.stage.FileChooser.ExtensionFilter;
import matcher.NameType;
import matcher.gui.Gui;
import matcher.gui.Gui.SelectedFile;
import matcher.type.ClassInstance;
import matcher.type.FieldInstance;
import matcher.type.Matchable;
import matcher.type.MemberInstance;
import matcher.type.MethodInstance;

final class IntermediaryGen {
	public static void init(Gui gui) {
		List<MenuItem> items = gui.getMenu().getMappingMenu().getItems();

		MenuItem menuItem = new MenuItem("Generate new intermediaries for B");
		menuItem.setOnAction(event -> generateIntermediaries(gui, false));
		items.add(menuItem);

		menuItem = new MenuItem("Generate continued intermediaries for B");
		menuItem.setOnAction(event -> generateIntermediaries(gui, true));
		items.add(menuItem);
	}

	private static void generateIntermediaries(Gui gui, boolean continued) {
		List<ExtensionFilter> extFilters = Collections.singletonList(new ExtensionFilter("counter file", "counter.txt"));
		SelectedFile counterFile = Gui.requestFile("Select intermediary counter file", gui.getScene().getWindow(), extFilters, continued);
		if (counterFile == null) return;

		// init counters

		int classCounter, methodCounter, fieldCounter;

		if (continued) {
			classCounter = -1;
			methodCounter = -1;
			fieldCounter = -1;

			try (BufferedReader reader = Files.newBufferedReader(counterFile.path)) {
				String line;

				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty()) continue;

					Matcher matcher = counterPattern.matcher(line);

					if (matcher.find()) {
						int counter = Integer.parseInt(matcher.group(2));

						switch (matcher.group(1)) {
						case counterNameClass:
							classCounter = counter;
							break;
						case counterNameMethod:
							methodCounter = counter;
							break;
						case counterNameField:
							fieldCounter = counter;
							break;
						default:
							throw new IllegalStateException();
						}
					}
				}

				if (classCounter < 0 || methodCounter < 0 || fieldCounter < 0) {
					throw new IOException("missing class, method or field counters");
				}
			} catch (Exception e) {
				e.printStackTrace();
				gui.showAlert(AlertType.ERROR, "Counter load error", "Error while loading intermediary counters", e.toString());
				return;
			}
		} else {
			classCounter = methodCounter = fieldCounter = 1;
		}

		// generate mappings

		List<ClassInstance> classes = new ArrayList<>(gui.getEnv().getClassesB());
		classes.sort(ClassInstance.nameComparator);

		List<MethodInstance> methods = new ArrayList<>();
		List<FieldInstance> fields = new ArrayList<>();

		for (ClassInstance cls : classes) {
			assert cls.isInput();

			if (needsIntermediaries(cls, continued)) {
				String name = String.format(classNameFormat, classCounter++);
				System.out.println("class "+cls+" -> "+name+(cls.hasMappedName() ? " ("+cls.getName(NameType.MAPPED_PLAIN)+")" : ""));
				cls.setAuxName(auxIndex, name);
			}

			for (MethodInstance method : cls.getMethods()) {
				if (needsIntermediaries(method, continued)) {
					methods.add(method);
				}
			}

			if (!methods.isEmpty()) {
				methods.sort(MemberInstance.nameComparator);

				for (MethodInstance method : methods) {
					String name = String.format(methodNameFormat, methodCounter++);
					System.out.println("method "+method+" -> "+name+(method.hasMappedName() ? " ("+method.getName(NameType.MAPPED_PLAIN)+")" : ""));

					for (MethodInstance m : method.getAllHierarchyMembers()) {
						m.setAuxName(auxIndex, name);
					}
				}

				methods.clear();
			}

			for (FieldInstance field : cls.getFields()) {
				if (needsIntermediaries(field, continued)) {
					fields.add(field);
				}
			}

			if (!fields.isEmpty()) {
				fields.sort(MemberInstance.nameComparator);

				for (FieldInstance field : fields) {
					String name = String.format(fieldNameFormat, fieldCounter++);
					System.out.println("field "+field+" -> "+name+(field.hasMappedName() ? " ("+field.getName(NameType.MAPPED_PLAIN)+")" : ""));
					field.setAuxName(auxIndex, name);

					assert field.getAllHierarchyMembers().size() == 1;
				}

				fields.clear();
			}
		}

		// save counters

		try (BufferedWriter writer = Files.newBufferedWriter(counterFile.path)) {
			writer.write(String.format(counterFormat, counterNameMethod, methodCounter));
			writer.write('\n');
			writer.write(String.format(counterFormat, counterNameField, fieldCounter));
			writer.write('\n');
			writer.write(String.format(counterFormat, counterNameClass, classCounter));
			writer.write('\n');
		} catch (Exception e) {
			e.printStackTrace();
			gui.showAlert(AlertType.ERROR, "Counter save error", "Error while saving intermediary counters", e.toString());
			return;
		}
	}

	private static boolean needsIntermediaries(Matchable<?> m, boolean continued) {
		return m.isNameObfuscated()
				&& (!continued || !m.hasAuxName(auxIndex) && (!m.hasMatch() || !m.getMatch().hasAuxName(auxIndex)));
	}

	private static final String counterNameClass = "class";
	private static final String counterNameMethod = "method";
	private static final String counterNameField = "field";
	private static final Pattern counterPattern = Pattern.compile(String.format("# INTERMEDIARY-COUNTER (%s|%s|%s) (\\d+)",
			counterNameClass, counterNameMethod, counterNameField));
	private static final String counterFormat = "# INTERMEDIARY-COUNTER %s %d";

	private static final String classNameFormat = "net/minecraft/class_%d";
	private static final String methodNameFormat = "method_%d";
	private static final String fieldNameFormat = "field_%d";

	private static final int auxIndex = 0;
}
