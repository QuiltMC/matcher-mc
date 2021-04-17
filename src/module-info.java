module matchermc {
	exports matchermc;

	requires com.google.gson;
	requires javafx.base;
	requires javafx.controls;
	requires javafx.graphics;
	requires matcher;

	provides matcher.Plugin with matchermc.MatcherPlugin;
}