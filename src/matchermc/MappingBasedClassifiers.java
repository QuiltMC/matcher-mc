package matchermc;

import java.util.Objects;

import matcher.NameType;
import matcher.classifier.ClassClassifier;
import matcher.classifier.FieldClassifier;
import matcher.classifier.MethodClassifier;
import matcher.type.ClassEnvironment;
import matcher.type.ClassInstance;
import matcher.type.FieldInstance;
import matcher.type.MethodInstance;

final class MappingBasedClassifiers {
	public static void init() {
		// TODO: disable these if any side doesn't have mc mappings
		ClassClassifier.addClassifier(new ClassClassifier.AbstractClassifier("mapped class name check") {
			@Override
			public double getScore(ClassInstance clsA, ClassInstance clsB, ClassEnvironment env) {
				NameType nt = MatcherPlugin.MC_MAPPING_FIELD.type;

				return Objects.equals(clsA.getName(nt), clsB.getName(nt)) ? 1 : 0;
			}
		}, weight);

		MethodClassifier.addClassifier(new MethodClassifier.AbstractClassifier("mapped method name check") {
			@Override
			public double getScore(MethodInstance methodA, MethodInstance methodB, ClassEnvironment env) {
				NameType nt = MatcherPlugin.MC_MAPPING_FIELD.type;

				return Objects.equals(methodA.getName(nt), methodB.getName(nt)) ? 1 : 0;
			}
		}, weight);

		FieldClassifier.addClassifier(new FieldClassifier.AbstractClassifier("mapped field name check") {
			@Override
			public double getScore(FieldInstance fieldA, FieldInstance fieldB, ClassEnvironment env) {
				NameType nt = MatcherPlugin.MC_MAPPING_FIELD.type;

				return Objects.equals(fieldA.getName(nt), fieldB.getName(nt)) ? 1 : 0;
			}
		}, weight);
	}

	private static final int weight = 30;
}
