package eu.efti.tst.core;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;

import javax.xml.XMLConstants;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Element tree of a consignment XSD with the subset annotations of every element. The names of the annotation
 * elements and attributes and the meaning of the status codes are declared by the format ({@link Annotations}).
 *
 * <p>Complex types are shared between contexts (e.g. the postal address of consignor and consignee), and such
 * elements carry one annotation per context. The annotation that applies is the one whose parent ID is the ID of
 * the annotation resolved for the parent element, so statuses are resolved along the path from the root. An element
 * without applicable annotation belongs to no subset in its context, and neither do its descendants. The
 * efti-data-tools library merges all annotations of an element instead, which makes subset filtering
 * over-inclusive for shared types; that is why this model is used.
 *
 * <p>Only the XSD constructs the eFTI schemas use are supported: named or anonymous complex types of the schema's
 * target namespace with element declarations in (nested) sequences, and simple content. Loading fails for anything
 * else (element references, groups, choices, wildcards, derived complex content, imported complex types with element
 * content, recursive types) and for elements whose annotation cannot be resolved unambiguously, rather than building
 * a wrong element tree.
 */
final class SubsetSchema {

    /** Meaning of a subset status code. */
    enum Status {
        /** The element must be present (with a value) if its parent is present. */
        MANDATORY,
        /** The element may be omitted. */
        OPTIONAL,
        /** Whether the element must be present depends on conditions the checks do not evaluate. */
        CONDITIONAL,
        /** An aggregate that only structures its child elements. */
        STRUCTURAL
    }

    /**
     * Vocabulary of the subset annotations: {@code <element id=".." parentId=".."><subset subsetId=".."
     * status=".."/></element>} (names as given here) in an {@code xsd:appinfo}, the annotation element in no
     * namespace.
     *
     * @param statusByCode meaning of every status code the XSD uses
     */
    record Annotations(String element, String id, String parentId, String subset, String subsetId, String status,
                       Map<String, Status> statusByCode) {

        Annotations {
            statusByCode = Map.copyOf(statusByCode);
        }

        Status statusOf(String code, String elementName) {
            Status status = statusByCode.get(code);
            if (status == null) {
                throw new IllegalArgumentException("The subset status '" + code + "' of element " + elementName
                        + " has no declared meaning");
            }
            return status;
        }
    }

    /** How the values of an element compare, as the built-in XSD type that its type derives from defines. */
    enum ValueType {
        /** xsd:decimal and the types derived from it: equal numbers are the same value in any notation. */
        NUMBER,
        /** xsd:string and xsd:normalizedString, i.e. free text: whitespace is part of the value. */
        TEXT,
        /** Every other built-in type, e.g. xsd:token for dates and codes: the XSD collapses whitespace. */
        TOKEN;

        static ValueType of(String builtInType) {
            return NUMERIC_XSD_TYPES.contains(builtInType) ? NUMBER
                    : WHITESPACE_PRESERVING_XSD_TYPES.contains(builtInType) ? TEXT : TOKEN;
        }
    }

    record Declaration(String name, boolean repeatable, ValueType valueType, Map<String, Status> statusBySubset, List<Declaration> children) {

        Optional<Declaration> child(String childName) {
            return children.stream().filter(child -> child.name.equals(childName)).findFirst();
        }

        boolean inAnyOf(Set<String> subsets) {
            return subsets.stream().anyMatch(statusBySubset::containsKey);
        }

        /** Subsets among the given ones in which this element has the given status. */
        Set<String> subsetsWithStatus(Status status, Set<String> subsets) {
            return subsets.stream().filter(subset -> status == statusBySubset.get(subset))
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        /** Whether the element belongs to some of the given subsets and is optional in all of those. */
        boolean optionalInAll(Set<String> subsets) {
            Set<String> applicable = subsets.stream().filter(statusBySubset::containsKey).collect(Collectors.toSet());
            return !applicable.isEmpty() && subsetsWithStatus(Status.OPTIONAL, applicable).equals(applicable);
        }

        /**
         * Subsets among the given ones in which this element is an aggregate that must be present if its parent is
         * present: it is structural there, not repeatable, and has a child that is mandatory or (recursively) such an
         * aggregate in the same subset. A repeatable aggregate (e.g. packages) may legitimately occur zero times.
         */
        Set<String> requiredAggregateIn(Set<String> subsets) {
            if (repeatable) {
                return Set.of();
            }
            return subsetsWithStatus(Status.STRUCTURAL, subsets).stream()
                    .filter(subset -> children.stream().anyMatch(child -> child.statusBySubset.get(subset) == Status.MANDATORY
                            || !child.requiredAggregateIn(Set.of(subset)).isEmpty()))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** xsd:decimal and the types derived from it. */
    private static final Set<String> NUMERIC_XSD_TYPES = Set.of("decimal", "integer", "nonPositiveInteger", "negativeInteger",
            "long", "int", "short", "byte", "nonNegativeInteger", "unsignedLong", "unsignedInt", "unsignedShort", "unsignedByte",
            "positiveInteger");
    private static final Set<String> WHITESPACE_PRESERVING_XSD_TYPES = Set.of("string", "normalizedString");

    private final String namespace;
    private final Declaration root;

    private SubsetSchema(String namespace, Declaration root) {
        this.namespace = namespace;
        this.root = root;
    }

    private static List<Element> xsdChildren(Element parent) {
        return Xml.childElements(parent).stream()
                .filter(child -> XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(child.getNamespaceURI()))
                .toList();
    }

    private static List<Element> xsdChildren(Element parent, String localName) {
        return xsdChildren(parent).stream().filter(child -> child.getLocalName().equals(localName)).toList();
    }

    private static List<Element> subsetAnnotations(Element element, Annotations vocabulary) {
        return xsdChildren(element, "annotation").stream()
                .flatMap(annotation -> xsdChildren(annotation, "appinfo").stream())
                .flatMap(appinfo -> Xml.childElements(appinfo).stream())
                .filter(child -> child.getNamespaceURI() == null && child.getLocalName().equals(vocabulary.element()))
                .toList();
    }

    private static String typeKey(String namespace, String name) {
        return "{" + Objects.requireNonNullElse(namespace, "") + "}" + name;
    }

    /** Collects the global type definitions of the schema and of the schemas it imports or includes. */
    private static void collectTypes(Path xsd, Element schema, Map<String, Element> types, Set<Path> visited) {
        if (!visited.add(xsd.toAbsolutePath().normalize())) {
            return;
        }
        String namespace = schema.getAttribute("targetNamespace");
        Stream.of("complexType", "simpleType")
                .flatMap(kind -> xsdChildren(schema, kind).stream())
                .forEach(type -> types.put(typeKey(namespace, type.getAttribute("name")), type));
        Stream.of("import", "include")
                .flatMap(kind -> xsdChildren(schema, kind).stream())
                .filter(reference -> reference.hasAttribute("schemaLocation"))
                .map(reference -> xsd.resolveSibling(reference.getAttribute("schemaLocation")))
                .forEach(location -> collectTypes(location, Xml.parse(location).getDocumentElement(), types, visited));
    }

    private static IllegalArgumentException unsupported(Element construct, String context) {
        return new IllegalArgumentException("xsd:" + construct.getLocalName() + " in " + context + " is not supported");
    }

    /** The element declarations of a content model, which may only consist of (nested) sequences. */
    private static List<Element> particles(Element model, String context) {
        List<Element> elements = new ArrayList<>();
        for (Element child : xsdChildren(model)) {
            switch (child.getLocalName()) {
                case "element" -> {
                    if (child.hasAttribute("ref")) {
                        throw unsupported(child, context + " (element reference " + child.getAttribute("ref") + ")");
                    }
                    elements.add(child);
                }
                case "sequence", "all" -> elements.addAll(particles(child, context));
                case "annotation", "attribute", "attributeGroup", "anyAttribute", "simpleContent" -> {
                }
                default -> throw unsupported(child, context);
            }
        }
        return elements;
    }

    /** The complex type with element content of an element declaration, empty if the element has simple content. */
    private static Optional<Element> complexType(Element element, Map<String, Element> types, String targetNamespace) {
        String name = element.getAttribute("name");
        if (!element.hasAttribute("type")) {
            return xsdChildren(element, "complexType").stream().findFirst();
        }
        String typeName = element.getAttribute("type");
        int colon = typeName.indexOf(':');
        String namespace = element.lookupNamespaceURI(colon < 0 ? null : typeName.substring(0, colon));
        if (XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(namespace)) {
            return Optional.empty();
        }
        Element type = types.get(typeKey(namespace, typeName.substring(colon + 1)));
        if (type == null) {
            throw new IllegalArgumentException("Type " + typeName + " of element " + name + " is not defined");
        }
        if (type.getLocalName().equals("simpleType") || particles(type, "type " + type.getAttribute("name")).isEmpty()) {
            return Optional.empty();
        }
        if (!targetNamespace.equals(namespace)) {
            throw new IllegalArgumentException("Element " + name + " has the imported complex type " + typeName
                    + " with element content, which is not supported");
        }
        return Optional.of(type);
    }

    /** The built-in XSD type that the type definition derives from, empty if it has none (e.g. element content). */
    private static Optional<String> builtInBase(Element type, Map<String, Element> types) {
        return Stream.concat(xsdChildren(type, "restriction").stream(), xsdChildren(type, "simpleContent").stream()
                        .flatMap(content -> Stream.of("extension", "restriction").flatMap(kind -> xsdChildren(content, kind).stream())))
                .findFirst()
                .flatMap(derivation -> builtInType(derivation, derivation.getAttribute("base"), types));
    }

    private static Optional<String> builtInType(Element context, String typeName, Map<String, Element> types) {
        int colon = typeName.indexOf(':');
        String namespace = context.lookupNamespaceURI(colon < 0 ? null : typeName.substring(0, colon));
        String localName = typeName.substring(colon + 1);
        return XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(namespace) ? Optional.of(localName)
                : Optional.ofNullable(types.get(typeKey(namespace, localName))).flatMap(type -> builtInBase(type, types));
    }

    /** How the content of the element compares; exactly, as text, if its type derives from no built-in type. */
    private static ValueType valueType(Element element, Map<String, Element> types) {
        Optional<String> builtIn = element.hasAttribute("type")
                ? builtInType(element, element.getAttribute("type"), types)
                : Stream.of("simpleType", "complexType").flatMap(kind -> xsdChildren(element, kind).stream())
                .findFirst().flatMap(type -> builtInBase(type, types));
        return builtIn.map(ValueType::of).orElse(ValueType.TEXT);
    }

    /** Whether the element or a sequence it is nested in (up to its complex type) may occur more than once. */
    private static boolean isRepeatable(Element element) {
        for (Node node = element; node instanceof Element particle && !particle.getLocalName().equals("complexType");
             node = node.getParentNode()) {
            String maxOccurs = particle.getAttribute("maxOccurs");
            if (maxOccurs.equals("unbounded") || (!maxOccurs.isEmpty() && Integer.parseInt(maxOccurs) > 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param inContext whether an annotation of a child element applies in the context of the parent element: every
     *                  annotation for the children of the root (which has no annotation), the annotations naming the
     *                  parent's annotation as parent otherwise, none if the parent has no annotation (it belongs to no
     *                  subset in this context, so neither do its children)
     */
    private static List<Declaration> declarations(Element type, Predicate<Element> inContext, Map<String, Element> types,
                                                  String targetNamespace, Annotations vocabulary, Set<Element> typesOnPath) {
        String typeName = type.hasAttribute("name") ? type.getAttribute("name") : "an anonymous type";
        if (typesOnPath.contains(type)) {
            throw new IllegalArgumentException("Type " + typeName + " is recursive, which is not supported");
        }
        Set<Element> path = new HashSet<>(typesOnPath);
        path.add(type);
        List<Declaration> declarations = new ArrayList<>();
        for (Element element : particles(type, "type " + typeName)) {
            String name = element.getAttribute("name");
            List<Element> annotations = subsetAnnotations(element, vocabulary).stream().filter(inContext).toList();
            if (annotations.size() > 1) {
                throw new IllegalArgumentException("Element " + name + " of type " + typeName + " has "
                        + annotations.size() + " subset annotations that apply in its context, which is ambiguous");
            }
            Map<String, Status> statusBySubset = annotations.stream()
                    .flatMap(annotation -> Xml.childElements(annotation).stream()
                            .filter(child -> child.getLocalName().equals(vocabulary.subset())))
                    .collect(Collectors.toMap(subset -> subset.getAttribute(vocabulary.subsetId()),
                            subset -> vocabulary.statusOf(subset.getAttribute(vocabulary.status()), name), (a, b) -> a));
            Predicate<Element> childContext = annotations.isEmpty()
                    ? annotation -> false
                    : annotation -> annotations.getFirst().getAttribute(vocabulary.id())
                    .equals(annotation.getAttribute(vocabulary.parentId()));
            List<Declaration> children = complexType(element, types, targetNamespace)
                    .map(childType -> declarations(childType, childContext, types, targetNamespace, vocabulary, path))
                    .orElse(List.of());
            declarations.add(new Declaration(name, isRepeatable(element), valueType(element, types), statusBySubset, children));
        }
        return List.copyOf(declarations);
    }

    /** Removes the element together with its indentation, so that the serialized result stays readable. */
    private static void remove(Element element) {
        Node previous = element.getPreviousSibling();
        if (previous instanceof Text text && text.getData().isBlank()) {
            element.getParentNode().removeChild(text);
        }
        element.getParentNode().removeChild(element);
    }

    private static void retain(Element element, Declaration declaration, Predicate<Declaration> keep) {
        for (Element child : Xml.childElements(element)) {
            Optional<Declaration> childDeclaration = declaration.child(child.getLocalName()).filter(keep);
            if (childDeclaration.isPresent()) {
                retain(child, childDeclaration.get(), keep);
            } else {
                remove(child);
            }
        }
    }

    private static void removeIndentation(Node node) {
        Node child = node.getFirstChild();
        while (child != null) {
            Node next = child.getNextSibling();
            if (child instanceof Text text && text.getData().isBlank()) {
                node.removeChild(child);
            } else {
                removeIndentation(child);
            }
            child = next;
        }
    }

    /** A copy without whitespace-only text, so that differently indented copies of an element compare as equal. */
    private static Node withoutIndentation(Element element) {
        Node copy = element.cloneNode(true);
        removeIndentation(copy);
        return copy;
    }

    /** The declaration of a child element if it belongs to one of the subsets. */
    private static Optional<Declaration> inSubsets(Declaration parent, Element child, Set<String> subsets) {
        return parent.child(child.getLocalName()).filter(declaration -> declaration.inAnyOf(subsets));
    }

    private static void collectOutside(Element element, Declaration declaration, String path, Set<String> subsets,
                                       List<String> outside) {
        for (Element child : Xml.childElements(element)) {
            String childPath = path + "/" + child.getLocalName();
            inSubsets(declaration, child, subsets).ifPresentOrElse(
                    childDeclaration -> collectOutside(child, childDeclaration, childPath, subsets, outside),
                    () -> outside.add(childPath));
        }
    }

    private static void collectViolations(Element element, Declaration declaration, String path, Set<String> subsets,
                                          List<String> violations) {
        List<Element> children = Xml.childElements(element);
        List<Node> comparable = children.stream().map(SubsetSchema::withoutIndentation).toList();
        for (int i = 0; i < children.size(); i++) {
            Element child = children.get(i);
            String childPath = path + "/" + child.getLocalName();
            Optional<Declaration> childDeclaration = inSubsets(declaration, child, subsets);
            if (childDeclaration.isEmpty()) {
                violations.add(childPath + " is not part of the requested subsets " + new TreeSet<>(subsets));
                continue;
            }
            if (comparable.subList(0, i).stream().anyMatch(comparable.get(i)::isEqualNode)) {
                violations.add(childPath + " occurs more than once with identical content");
            }
            collectViolations(child, childDeclaration.get(), childPath, subsets, violations);
        }
        for (Declaration childDeclaration : declaration.children) {
            Set<String> mandatoryIn = childDeclaration.subsetsWithStatus(Status.MANDATORY, subsets);
            Set<String> aggregateIn = childDeclaration.requiredAggregateIn(subsets);
            if (mandatoryIn.isEmpty() && aggregateIn.isEmpty()) {
                continue;
            }
            String childPath = path + "/" + childDeclaration.name;
            List<Element> present = children.stream().filter(c -> c.getLocalName().equals(childDeclaration.name)).toList();
            if (present.isEmpty()) {
                violations.add(childPath + (mandatoryIn.isEmpty()
                        ? " holds mandatory elements of " + aggregateIn + " but is missing"
                        : " is mandatory in " + mandatoryIn + " but missing"));
            } else if (!mandatoryIn.isEmpty() && present.stream().anyMatch(c -> c.getTextContent().isBlank())) {
                violations.add(childPath + " is mandatory in " + mandatoryIn + " but empty");
            }
        }
    }

    /**
     * @throws IllegalArgumentException if the XSD lacks the root element, uses an unsupported construct (see above)
     *                                  or a status code without meaning
     */
    static SubsetSchema load(Path xsd, String rootElementName, Annotations vocabulary) {
        Element schema = Xml.parse(xsd).getDocumentElement();
        String namespace = schema.getAttribute("targetNamespace");
        Element rootElement = xsdChildren(schema, "element").stream()
                .filter(element -> element.getAttribute("name").equals(rootElementName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(xsd + " declares no element " + rootElementName));
        List<Declaration> children;
        try {
            Map<String, Element> types = new HashMap<>();
            collectTypes(xsd, schema, types, new HashSet<>());
            children = complexType(rootElement, types, namespace)
                    .map(type -> declarations(type, annotation -> true, types, namespace, vocabulary, Set.of()))
                    .orElse(List.of());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(xsd + ": " + e.getMessage(), e);
        }
        return new SubsetSchema(namespace, new Declaration(rootElementName, false, ValueType.TEXT, Map.of(), children));
    }

    String namespace() {
        return namespace;
    }

    boolean isRoot(Element element) {
        return root.name.equals(element.getLocalName()) && namespace.equals(element.getNamespaceURI());
    }

    /** Removes every descendant of the root element that belongs to none of the subsets. */
    void filter(Element rootElement, Set<String> subsets) {
        retain(rootElement, root, declaration -> declaration.inAnyOf(subsets));
    }

    /** Removes every descendant of the root element that belongs to none of the subsets or is optional in all of them. */
    void retainNotOmissible(Element rootElement, Set<String> subsets) {
        retain(rootElement, root, declaration -> declaration.inAnyOf(subsets) && !declaration.optionalInAll(subsets));
    }

    /** Removes every descendant of the root element that this schema does not declare. */
    void dropUndeclared(Element rootElement) {
        retain(rootElement, root, declaration -> true);
    }

    /** Paths of the descendants of the root element that belong to none of the subsets (not of their descendants). */
    List<String> elementsOutside(Element rootElement, Set<String> subsets) {
        List<String> outside = new ArrayList<>();
        collectOutside(rootElement, root, "/" + root.name, subsets, outside);
        return outside;
    }

    /**
     * Violations of the composition rules for a data set restricted to the subsets:
     * <ul>
     *   <li>no element outside the subsets;</li>
     *   <li>no sibling elements with the same name and identical content (attributes, text and children), as each
     *       element is returned once even if it belongs to several subsets;</li>
     *   <li>elements that are mandatory in one of the subsets are present and non-empty if their
     *       parent is present;</li>
     *   <li>aggregates (structural elements) that hold mandatory elements of one of the subsets, directly or via
     *       further such aggregates, are present if their parent is present, unless they are repeatable.</li>
     * </ul>
     * Conditional elements are not checked.
     */
    List<String> compositionViolations(Element rootElement, Set<String> subsets) {
        List<String> violations = new ArrayList<>();
        collectViolations(rootElement, root, "/" + root.name, subsets, violations);
        return violations;
    }

    private static Stream<String> subsetsBelow(Declaration declaration) {
        return Stream.concat(declaration.statusBySubset.keySet().stream(),
                declaration.children.stream().flatMap(SubsetSchema::subsetsBelow));
    }

    /** All subsets the schema assigns elements to. */
    Set<String> subsets() {
        return subsetsBelow(root).collect(Collectors.toSet());
    }

    /**
     * How the values at the path (local names below the root) compare; exactly, as text, if the schema does not
     * declare an element there (e.g. an attribute).
     */
    ValueType valueType(List<String> path) {
        Optional<Declaration> current = Optional.of(root);
        for (String name : path) {
            current = current.flatMap(declaration -> declaration.child(name));
        }
        return current.map(Declaration::valueType).orElse(ValueType.TEXT);
    }

    /**
     * Whether the element at the path (local names below the root) or one of its ancestors is optional in all of
     * the subsets it belongs to, so that a platform may omit it.
     */
    boolean optionalInAll(List<String> path, Set<String> subsets) {
        Declaration current = root;
        for (String name : path) {
            Optional<Declaration> child = current.child(name);
            if (child.isEmpty()) {
                return false;
            }
            current = child.get();
            if (current.optionalInAll(subsets)) {
                return true;
            }
        }
        return false;
    }
}
