package com.example.xjc;

import com.sun.codemodel.*;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.model.CEnumLeafInfo;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.Outline;
import com.sun.xml.xsom.*;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Pour chaque propriété, génère des constantes statiques + getters statiques :
 * bornes, longueurs, digits, pattern, whiteSpace, enumeration (si pas d'enum Java),
 * et minOccurs / maxOccurs.
 */
public class RestrictionsPlugin extends Plugin {

    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";
    private static final Set<String> NUMERIC_PRIMITIVES = Set.of("decimal", "float", "double");

    private static final Map<String, String> BOUNDS = Map.of(
            XSFacet.FACET_MININCLUSIVE, "MIN_INCLUSIVE",
            XSFacet.FACET_MAXINCLUSIVE, "MAX_INCLUSIVE",
            XSFacet.FACET_MINEXCLUSIVE, "MIN_EXCLUSIVE",
            XSFacet.FACET_MAXEXCLUSIVE, "MAX_EXCLUSIVE");

    private static final Map<String, String> INTS = Map.of(
            XSFacet.FACET_LENGTH, "LENGTH",
            XSFacet.FACET_MINLENGTH, "MIN_LENGTH",
            XSFacet.FACET_MAXLENGTH, "MAX_LENGTH",
            XSFacet.FACET_TOTALDIGITS, "TOTAL_DIGITS",
            XSFacet.FACET_FRACTIONDIGITS, "FRACTION_DIGITS");

    @Override
    public String getOptionName() {
        return "XaddRestrictions";
    }

    @Override
    public String getUsage() {
        return "  -XaddRestrictions : génère constantes et getters pour les facettes XSD et les occurs";
    }

    /** Résultat de l'analyse de la chaîne de dérivation d'un type simple. */
    private static final class Facets {
        final Map<String, String> single = new LinkedHashMap<>();
        final List<String> patterns = new ArrayList<>();
        final List<String> enums = new ArrayList<>();
        boolean numeric;
    }

    @Override
    public boolean run(Outline outline, Options opt, ErrorHandler eh) throws SAXException {
        JCodeModel cm = outline.getCodeModel();

        for (ClassOutline co : outline.getClasses()) {
            JDefinedClass cls = co.implClass;

            for (CPropertyInfo prop : co.target.getProperties()) {
                String base = upperSnake(prop.getName(false));
                String camel = prop.getName(true);

                // ---------- minOccurs / maxOccurs ----------
                XSComponent src = prop.getSchemaComponent();
                if (src instanceof XSParticle) {
                    XSParticle p = (XSParticle) src;
                    addConstant(cls, base, camel, "MIN_OCCURS", cm.INT,
                            JExpr.lit(toInt(p.getMinOccurs())));
                    // unbounded => Integer.MAX_VALUE
                    addConstant(cls, base, camel, "MAX_OCCURS", cm.INT,
                            JExpr.lit(toInt(p.getMaxOccurs())));
                }

                // ---------- facettes ----------
                XSSimpleType st = simpleTypeOf(prop);
                if (st == null) {
                    continue;
                }
                Facets f = collect(st);
                boolean javaEnum = prop.ref().stream().anyMatch(t -> t instanceof CEnumLeafInfo);

                for (Map.Entry<String, String> e : f.single.entrySet()) {
                    String name = e.getKey();
                    String value = e.getValue();
                    if (BOUNDS.containsKey(name)) {
                        addBound(cm, cls, base, camel, BOUNDS.get(name), value, f.numeric);
                    } else if (INTS.containsKey(name)) {
                        addConstant(cls, base, camel, INTS.get(name), cm.INT, JExpr.lit(toInt(value)));
                    } else if (XSFacet.FACET_WHITESPACE.equals(name)) {
                        addConstant(cls, base, camel, "WHITE_SPACE", cm.ref(String.class), JExpr.lit(value));
                    }
                }

                if (!f.patterns.isEmpty()) {
                    addPattern(cm, cls, prop, base, camel, f.patterns, eh);
                }

                if (!f.enums.isEmpty() && !javaEnum) {
                    JClass listOfString = cm.ref(List.class).narrow(String.class);
                    JInvocation of = cm.ref(List.class).staticInvoke("of");
                    f.enums.forEach(v -> of.arg(JExpr.lit(v)));
                    addConstant(cls, base, camel, "ENUMERATION", listOfString, of);
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Génération
    // ------------------------------------------------------------------

    private static void addConstant(JDefinedClass cls, String base, String camel,
                                    String suffix, JType type, JExpression init) {
        String constName = base + "_" + suffix;
        if (cls.fields().containsKey(constName)) {
            return;
        }
        cls.field(JMod.PUBLIC | JMod.STATIC | JMod.FINAL, type, constName, init);
        JMethod getter = cls.method(JMod.PUBLIC | JMod.STATIC, type, "get" + camel + toCamel(suffix));
        getter.body()._return(JExpr.ref(constName));
    }

    private static void addBound(JCodeModel cm, JDefinedClass cls, String base, String camel,
                                 String suffix, String value, boolean numeric) {
        if (numeric && parseDecimal(value) != null) {
            JClass bd = cm.ref(BigDecimal.class);
            addConstant(cls, base, camel, suffix, bd, JExpr._new(bd).arg(JExpr.lit(value)));
        } else {
            // dates, durées, etc. : valeur lexicale conservée telle quelle
            addConstant(cls, base, camel, suffix, cm.ref(String.class), JExpr.lit(value));
        }
    }

    private static void addPattern(JCodeModel cm, JDefinedClass cls, CPropertyInfo prop,
                                   String base, String camel, List<String> patterns,
                                   ErrorHandler eh) throws SAXException {
        // plusieurs <xs:pattern> au même niveau = OU
        String regex = patterns.size() == 1
                ? patterns.get(0)
                : patterns.stream().map(p -> "(?:" + p + ")").collect(Collectors.joining("|"));

        addConstant(cls, base, camel, "PATTERN_REGEX", cm.ref(String.class), JExpr.lit(regex));

        try {
            Pattern.compile(regex); // vérifié à la génération, pas à l'exécution
            JClass patternType = cm.ref(Pattern.class);
            addConstant(cls, base, camel, "PATTERN", patternType,
                    patternType.staticInvoke("compile").arg(JExpr.ref(base + "_PATTERN_REGEX")));
        } catch (PatternSyntaxException ex) {
            eh.warning(new SAXParseException(
                    "Pattern XSD non compilable en Java pour '" + prop.getName(false)
                            + "' (seule la constante String est générée) : " + regex,
                    prop.getLocator()));
        }
    }

    // ------------------------------------------------------------------
    // Analyse XSOM
    // ------------------------------------------------------------------

    private static XSSimpleType simpleTypeOf(CPropertyInfo prop) {
        XSComponent c = prop.getSchemaComponent();
        if (c instanceof XSParticle) {
            c = ((XSParticle) c).getTerm();
        }
        if (c instanceof XSAttributeUse) {
            c = ((XSAttributeUse) c).getDecl();
        }

        XSType type = null;
        if (c instanceof XSElementDecl) {
            type = ((XSElementDecl) c).getType();
        } else if (c instanceof XSAttributeDecl) {
            type = ((XSAttributeDecl) c).getType();
        } else if (c instanceof XSType) {
            type = (XSType) c;
        } else if (c instanceof XSContentType) {
            return ((XSContentType) c).asSimpleType();
        }
        if (type == null) {
            return null;
        }
        if (type.isSimpleType()) {
            return type.asSimpleType();
        }
        // complexType à contenu simple (xs:simpleContent)
        if (type.isComplexType()) {
            return type.asComplexType().getContentType().asSimpleType();
        }
        return null;
    }

    /**
     * Remonte la chaîne de dérivation jusqu'au type built-in XSD (exclu), le plus proche gagne.
     * Les facettes built-in (ex. maxInclusive de xs:int) sont volontairement ignorées.
     */
    private static Facets collect(XSSimpleType type) {
        Facets f = new Facets();
        XSSimpleType prim = type.getPrimitiveType();
        f.numeric = prim != null && NUMERIC_PRIMITIVES.contains(prim.getName());

        XSSimpleType t = type;
        while (t != null && t.isRestriction() && !XSD_NS.equals(t.getTargetNamespace())) {
            XSRestrictionSimpleType r = t.asRestriction();
            List<String> levelPatterns = new ArrayList<>();
            List<String> levelEnums = new ArrayList<>();

            Iterator<XSFacet> it = r.iterateDeclaredFacets();
            while (it.hasNext()) {
                XSFacet facet = it.next();
                String value = facet.getValue().value;
                switch (facet.getName()) {
                    case XSFacet.FACET_PATTERN:
                        levelPatterns.add(value);
                        break;
                    case XSFacet.FACET_ENUMERATION:
                        levelEnums.add(value);
                        break;
                    default:
                        f.single.putIfAbsent(facet.getName(), value);
                }
            }
            if (f.patterns.isEmpty()) {
                f.patterns.addAll(levelPatterns);
            }
            if (f.enums.isEmpty()) {
                f.enums.addAll(levelEnums);
            }
            t = r.getSimpleBaseType();
        }
        return f;
    }

    // ------------------------------------------------------------------
    // Utilitaires
    // ------------------------------------------------------------------

    /** Accepte int ou BigInteger selon la version de XSOM (String.valueOf(Object) compile dans les deux cas). */
    private static int toInt(Object occurs) {
        BigInteger v = new BigInteger(String.valueOf(occurs));
        if (v.signum() < 0 || v.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            return Integer.MAX_VALUE; // unbounded (-1) ou énorme
        }
        return v.intValue();
    }

    private static BigDecimal parseDecimal(String s) {
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null; // INF, NaN...
        }
    }

    private static String upperSnake(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String toCamel(String upperSnake) {
        StringBuilder sb = new StringBuilder();
        for (String part : upperSnake.split("_")) {
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}


package com.example.annotation;

import java.util.List;
import java.util.regex.Pattern;

public record FieldConstraints(
        String name, int minOccurs, int maxOccurs,
        Object minInclusive, Object maxInclusive, Object minExclusive, Object maxExclusive,
        Integer length, Integer minLength, Integer maxLength,
        Integer totalDigits, Integer fractionDigits,
        Pattern pattern, List<String> enumeration, String whiteSpace) {

    public static Builder of(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private int minOccurs = 0, maxOccurs = 1;
        private Object minInc, maxInc, minExc, maxExc;
        private Integer length, minLength, maxLength, totalDigits, fractionDigits;
        private Pattern pattern;
        private List<String> enumeration;
        private String whiteSpace;

        private Builder(String name) { this.name = name; }

        public Builder occurs(int min, int max)  { minOccurs = min; maxOccurs = max; return this; }
        public Builder minInclusive(Object v)    { minInc = v; return this; }
        public Builder maxInclusive(Object v)    { maxInc = v; return this; }
        public Builder minExclusive(Object v)    { minExc = v; return this; }
        public Builder maxExclusive(Object v)    { maxExc = v; return this; }
        public Builder length(int v)             { length = v; return this; }
        public Builder minLength(int v)          { minLength = v; return this; }
        public Builder maxLength(int v)          { maxLength = v; return this; }
        public Builder totalDigits(int v)        { totalDigits = v; return this; }
        public Builder fractionDigits(int v)     { fractionDigits = v; return this; }
        public Builder pattern(Pattern v)        { pattern = v; return this; }
        public Builder enumeration(List<String> v) { enumeration = v; return this; }
        public Builder whiteSpace(String v)      { whiteSpace = v; return this; }

        public FieldConstraints build() {
            return new FieldConstraints(name, minOccurs, maxOccurs, minInc, maxInc, minExc, maxExc,
                    length, minLength, maxLength, totalDigits, fractionDigits,
                    pattern, enumeration, whiteSpace);
        }
    }
}

package com.example.annotation;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.*;

public final class ConstraintValidator {

    public record Violation(String path, String rule, String message) {}

    private ConstraintValidator() {}

    // ---------- Niveau 3 : validation complète, récursive ----------
    public static List<Violation> validate(Object root) {
        List<Violation> out = new ArrayList<>();
        validate(root, "", out);
        return out;
    }

    // ---------- Niveau 1 : peut-on encore ajouter ? (ne regarde que la taille) ----------
    public static boolean canAdd(Object bean, String field) {
        FieldConstraints c = find(bean.getClass(), field);
        Object v = read(bean.getClass(), bean, field);
        int size = v instanceof Collection<?> col ? col.size() : (v == null ? 0 : 1);
        return size < c.maxOccurs();
    }

    // ---------- Niveau 2 : facettes d'un seul élément ----------
    public static List<Violation> validateValue(Object bean, String field, Object value) {
        List<Violation> out = new ArrayList<>();
        checkValue(find(bean.getClass(), field), value, field, out);
        return out;
    }

    // ------------------------------------------------------------------

    private static void validate(Object bean, String path, List<Violation> out) {
        if (bean == null || bean.getClass().getName().startsWith("java.")) {
            return;
        }
        for (Class<?> k = bean.getClass(); k != null; k = k.getSuperclass()) {
            for (Map.Entry<String, FieldConstraints> e : declared(k).entrySet()) {
                String p = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                Object v = read(k, bean, e.getKey());
                boolean isList = v instanceof Collection<?>;
                List<Object> items = v == null ? List.of()
                        : isList ? new ArrayList<Object>((Collection<?>) v) : List.of(v);
                FieldConstraints c = e.getValue();

                if (items.size() < c.minOccurs()) {
                    out.add(new Violation(p, "minOccurs",
                            items.size() + " élément(s), minimum " + c.minOccurs()));
                }
                if (items.size() > c.maxOccurs()) {
                    out.add(new Violation(p, "maxOccurs",
                            items.size() + " élément(s), maximum " + c.maxOccurs()));
                }
                for (int i = 0; i < items.size(); i++) {
                    String ip = isList ? p + "[" + i + "]" : p;
                    checkValue(c, items.get(i), ip, out);
                    validate(items.get(i), ip, out);   // descente dans les objets générés
                }
            }
        }
    }

    private static void checkValue(FieldConstraints c, Object item, String p, List<Violation> out) {
        if (item == null) {
            return;
        }
        if (c.enumeration() != null && !c.enumeration().contains(String.valueOf(item))) {
            out.add(new Violation(p, "enumeration", "'" + item + "' not in " + c.enumeration()));
        }
        if (item instanceof String s) {
            int len = s.codePointCount(0, s.length());
            if (c.length() != null && len != c.length())
                out.add(new Violation(p, "length", len + " caractères, attendu " + c.length()));
            if (c.minLength() != null && len < c.minLength())
                out.add(new Violation(p, "minLength", len + " < " + c.minLength()));
            if (c.maxLength() != null && len > c.maxLength())
                out.add(new Violation(p, "maxLength", len + " > " + c.maxLength()));
            if (c.pattern() != null && !c.pattern().matcher(s).matches())
                out.add(new Violation(p, "pattern", "'" + s + "' ne respecte pas " + c.pattern()));
        }
        if (item instanceof Number n) {
            BigDecimal d;
            try {
                d = new BigDecimal(n.toString());
            } catch (NumberFormatException ex) {
                return; // NaN, Infinity
            }
            if (c.minInclusive() instanceof BigDecimal b && d.compareTo(b) < 0)
                out.add(new Violation(p, "minInclusive", d + " < " + b));
            if (c.maxInclusive() instanceof BigDecimal b && d.compareTo(b) > 0)
                out.add(new Violation(p, "maxInclusive", d + " > " + b));
            if (c.minExclusive() instanceof BigDecimal b && d.compareTo(b) <= 0)
                out.add(new Violation(p, "minExclusive", d + " <= " + b));
            if (c.maxExclusive() instanceof BigDecimal b && d.compareTo(b) >= 0)
                out.add(new Violation(p, "maxExclusive", d + " >= " + b));

            BigDecimal s = d.stripTrailingZeros();
            if (s.scale() < 0) s = s.setScale(0);
            if (c.totalDigits() != null && s.precision() > c.totalDigits())
                out.add(new Violation(p, "totalDigits", s.precision() + " > " + c.totalDigits()));
            if (c.fractionDigits() != null && s.scale() > c.fractionDigits())
                out.add(new Violation(p, "fractionDigits", s.scale() + " > " + c.fractionDigits()));
        }
    }

    // ---------- réflexion ----------

    @SuppressWarnings("unchecked")
    private static Map<String, FieldConstraints> declared(Class<?> k) {
        try {
            return (Map<String, FieldConstraints>) k.getDeclaredField("CONSTRAINTS").get(null);
        } catch (NoSuchFieldException e) {
            return Map.of();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldConstraints find(Class<?> cls, String field) {
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            FieldConstraints c = declared(k).get(field);
            if (c != null) return c;
        }
        throw new IllegalArgumentException("Pas de contraintes pour " + cls.getName() + "." + field);
    }

    private static Object read(Class<?> start, Object bean, String field) {
        for (Class<?> k = start; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(bean);
            } catch (NoSuchFieldException ignored) {
                // on remonte la hiérarchie
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("Champ introuvable : " + field);
    }
}

private static final String[][] LINKS = {
        {"MIN_INCLUSIVE", "minInclusive"}, {"MAX_INCLUSIVE", "maxInclusive"},
        {"MIN_EXCLUSIVE", "minExclusive"}, {"MAX_EXCLUSIVE", "maxExclusive"},
        {"LENGTH", "length"}, {"MIN_LENGTH", "minLength"}, {"MAX_LENGTH", "maxLength"},
        {"TOTAL_DIGITS", "totalDigits"}, {"FRACTION_DIGITS", "fractionDigits"},
        {"PATTERN", "pattern"}, {"ENUMERATION", "enumeration"}, {"WHITE_SPACE", "whiteSpace"}};

/** Construit l'expression FieldConstraints.of("x").occurs(..).xxx(..).build() à partir des constantes déjà générées. */
private static JExpression entry(JCodeModel cm, JDefinedClass cls, CPropertyInfo prop, String base) {
    JClass fc = cm.directClass("com.example.annotation.FieldConstraints");
    JExpression chain = fc.staticInvoke("of").arg(JExpr.lit(prop.getName(false)));

    if (cls.fields().containsKey(base + "_MIN_OCCURS")) {
        chain = JExpr.invoke(chain, "occurs")
                .arg(JExpr.ref(base + "_MIN_OCCURS"))
                .arg(JExpr.ref(base + "_MAX_OCCURS"));
    }
    for (String[] l : LINKS) {
        if (cls.fields().containsKey(base + "_" + l[0])) {
            chain = JExpr.invoke(chain, l[1]).arg(JExpr.ref(base + "_" + l[0]));
        }
    }
    return cm.ref(Map.class).staticInvoke("entry")
            .arg(JExpr.lit(prop.getName(false)))
            .arg(JExpr.invoke(chain, "build"));
}


for (ClassOutline co : outline.getClasses()) {
    JDefinedClass cls = co.implClass;
    List<JExpression> entries = new ArrayList<>();          // <-- AJOUT

    for (CPropertyInfo prop : co.target.getProperties()) {
        String base = upperSnake(prop.getName(false));
        String camel = prop.getName(true);

        // ... bloc minOccurs / maxOccurs inchangé ...

        XSSimpleType st = simpleTypeOf(prop);
        if (st == null) {
            entries.add(entry(cm, cls, prop, base));        // <-- AJOUT
            continue;
        }

        // ... bloc facettes inchangé ...

        entries.add(entry(cm, cls, prop, base));            // <-- AJOUT (fin du corps de boucle)
    }

    // <-- AJOUT : la map, après la boucle des propriétés
    if (!entries.isEmpty()) {
        JClass fc = cm.directClass("com.example.annotation.FieldConstraints");
        JClass mapType = cm.ref(Map.class).narrow(cm.ref(String.class), fc);
        JInvocation ofEntries = cm.ref(Map.class).staticInvoke("ofEntries");
        entries.forEach(ofEntries::arg);
        cls.field(JMod.PUBLIC | JMod.STATIC | JMod.FINAL, mapType, "CONSTRAINTS", ofEntries);
        cls.method(JMod.PUBLIC | JMod.STATIC, mapType, "getConstraints")
           .body()._return(JExpr.ref("CONSTRAINTS"));
    }
}


