package matchermc;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
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
import matcher.mapping.MappingField;
import matcher.mapping.MappingFormat;
import matcher.mapping.MappingReader;
import matcher.mapping.Mappings;
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

		List<Function<List<Path>, Path>> inputsA = new ArrayList<>();
		List<Function<List<Path>, Path>> inputsB = new ArrayList<>();
		Set<InputFile> libsA = new LinkedHashSet<>();
		Set<InputFile> libsB = new LinkedHashSet<>();
		List<Function<List<Path>, Path>> mappingsA = new ArrayList<>();
		List<Function<List<Path>, Path>> mappingsB = new ArrayList<>();

		getLibs(jsonA.path, inputsA, libsA, mappingsA);
		getLibs(jsonB.path, inputsB, libsB, mappingsB);

		Set<InputFile> libsCommon = new LinkedHashSet<>(libsA);
		libsCommon.retainAll(libsB);
		libsA.removeAll(libsCommon);
		libsB.removeAll(libsCommon);

		System.out.println("inputs A: "+inputsA);
		System.out.println("inputs B: "+inputsB);
		System.out.println("common libs: "+libsCommon);
		System.out.println("libs A: "+libsA);
		System.out.println("libs B: "+libsB);
		System.out.println("mappings A: "+mappingsA);
		System.out.println("mappings B: "+mappingsB);

		gui.getMenu().getFileMenu().requestProjectLoadSettings(loadSettings -> {
			List<Path> inputDirs = loadSettings.paths;

			try {
				List<Path> pathsA = resolve(inputsA, inputDirs);
				List<Path> pathsB = resolve(inputsB, inputDirs);
				List<Path> classPathA = Matcher.resolvePaths(inputDirs, libsA);
				List<Path> classPathB = Matcher.resolvePaths(inputDirs, libsB);
				List<Path> sharedClassPath = Matcher.resolvePaths(inputDirs, libsCommon);
				List<Path> mappingsPathsA = resolve(mappingsA, inputDirs);
				List<Path> mappingsPathsB = resolve(mappingsB, inputDirs);

				ProjectConfig prevConfig = Config.getProjectConfig();
				ProjectConfig config = new ProjectConfig(pathsA, pathsB, classPathA, classPathB, sharedClassPath,
						prevConfig.hasInputsBeforeClassPath(),
						nonObfuscatedClassPattern, nonObfuscatedClassPattern,
						prevConfig.getNonObfuscatedMemberPatternA(), prevConfig.getNonObfuscatedMemberPatternB());

				gui.getMenu().getFileMenu().newProject(config).thenAccept(loaded -> {
					if (!loaded) return;

					for (int i = 0; i < 2; i++) {
						boolean a = i == 0;

						for (Path mappingsFile : (a ? mappingsPathsA : mappingsPathsB)) {
							try {
								Mappings.load(mappingsFile, MappingFormat.PROGUARD,
										MappingReader.NS_TARGET_FALLBACK, MappingReader.NS_SOURCE_FALLBACK,
										MappingField.PLAIN, MatcherPlugin.MC_MAPPING_FIELD,
										(a ? gui.getEnv().getEnvA() : gui.getEnv().getEnvB()),
										false);
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						}
					}
				});
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	private static void getLibs(Path jsonFile, List<Function<List<Path>, Path>> inputsOut, Set<InputFile> libsOut, List<Function<List<Path>, Path>> mappingsOut) {
		JsonElement jsonElem;

		try (Reader reader = Files.newBufferedReader(jsonFile)) {
			jsonElem = new JsonParser().parse(reader);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		JsonObject json = jsonElem.getAsJsonObject();

		JsonElement downloads = json.get("downloads");

		if (downloads != null) {
			for (String type : new String[] { "client", "server", "client_mappings" }) {
				JsonElement e = downloads.getAsJsonObject().get(type);
				if (e == null) continue;

				JsonObject o = e.getAsJsonObject();

				JsonElement size = o.get("size");
				JsonElement hash = o.get("sha1");

				if (size != null && hash != null) {
					Function<List<Path>, Path> item = new HashInputResolver(type, size.getAsLong(), decodeHex(hash.getAsString()));

					if (type.endsWith("_mappings")) {
						mappingsOut.add(item);
					} else {
						inputsOut.add(item);
					}
				}
			}
		}

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

	private static List<Path> resolve(List<Function<List<Path>, Path>> inputs, List<Path> inputDirs) {
		List<Path> ret = new ArrayList<>(inputs.size());

		for (Function<List<Path>, Path> input : inputs) {
			Path res = input.apply(inputDirs);
			if (res == null) throw new RuntimeException("can't resolve "+input);

			ret.add(res);
		}

		return ret;
	}

	private static byte[] decodeHex(String hex) {
		int len = hex.length();
		if ((len & 1) != 0) throw new IllegalArgumentException("uneven hex str len");

		byte[] ret = new byte[len / 2];

		for (int i = 0; i < len; i += 2) {
			ret[i >>> 1] = (byte) (decodeHex(hex.charAt(i)) << 4 | decodeHex(hex.charAt(i + 1)));
		}

		return ret;
	}

	private static int decodeHex(char c) {
		if (c >= '0' && c <= '9') {
			return c - '0';
		} else if (c >= 'a' && c <= 'f') {
			return 10 + c - 'a';
		} else if (c >= 'A' && c <= 'F') {
			return 10 + c - 'A';
		} else {
			throw new IllegalArgumentException("no hex char: "+c);
		}
	}

	private static MessageDigest getMd() {
		try {
			return MessageDigest.getInstance(new String("SHA-1"));
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	private static byte[] hash(Path file, MessageDigest md) throws IOException {
		md.reset();
		ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);

		try (ByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
			while (channel.read(buffer) >= 0) {
				buffer.flip();
				md.update(buffer);
				buffer.clear();
			}
		}

		return md.digest();
	}

	private static final class HashInputResolver extends SimpleFileVisitor<Path> implements Function<List<Path>, Path> {
		public HashInputResolver(String name, long size, byte[] hash) {
			this.name = name;
			this.size = size;
			this.hash = hash;
		}

		@Override
		public Path apply(List<Path> inputPaths) {
			for (Path dir : inputPaths) {
				try {
					Files.walkFileTree(dir, this);
					if (result != null) break;
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}

			return result;
		}

		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
			if (attrs.size() == size && Arrays.equals(hash(file, getMd()), hash)) {
				result = file;

				return FileVisitResult.TERMINATE;
			} else {
				return FileVisitResult.CONTINUE;
			}
		}

		@Override
		public String toString() {
			return String.format("%s (%d bytes)", name, size);
		}

		private final String name;
		private final long size;
		private final byte[] hash;
		private Path result;
	}

	private static final String osName = "linux";
	private static final String nonObfuscatedClassPattern = ".+/.+"; // anything not in the root package
}
