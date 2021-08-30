package sdtest;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.util.Collection;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElements;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

/**
 * This class allows XML paths to be created (as strings) for a JAXB representation of an XML message.
 * <p>
 * Usage is as follows:
 * <ol>
 *   <li>Create an instance of this class (passing in the root node of the XML document).</li>
 *   <li>Call forNode() - i.e. specifying the node (and optionally the field) in error.</li>
 *   <li>Call toString() - the path will be generated from the root node down to the node in error.</li>
 * </ol>
 * <p>
 * This class is an interesting use of reflection and annotations. It is useful when a developer wants to generate an error message
 * that includes the XML-path to the relevant node in error. It saves the developer from manually building the XML-path.
 * <p>
 * WARNING: you need to be careful of the case where a single node contains two fields of the same simple type (e.g. Boolean). In
 * the case where those two fields had the same value such that Boolean.valueOf() had returned the same Boolean instance, the path
 * could point to the wrong field.
 */
public final class JaxbXmlPath
{
    private final Object mRootNode;
    private final Object mJaxbParentNode;
    private final Object mFieldValue;

    /**
     * Create a JAXB XML path for the passed root node.
     */
    public JaxbXmlPath(Object inRootNode)
    {
        mRootNode = inRootNode;
        mJaxbParentNode = null;
        mFieldValue = null;
    }

    private JaxbXmlPath(Object inRootNode, Object inJaxbParentNode, Object inFieldValue)
    {
        mRootNode = inRootNode;
        mJaxbParentNode = inJaxbParentNode;
        mFieldValue = inFieldValue;
    }

    /**
     * Returns a path for the passed JAXB node (which can be the root node or any child node).
     * <p>
     * Note that the passed node should not be a simple field (Integer/Enum/String/etc) as such elements can be duplicated within
     * the xml tree. Only a JAXB element (or a collection) can be passed in. An exception will be thrown if any other type of
     * Object is passed (i.e. any simple field).
     */
    public JaxbXmlPath forNode(Object inJaxbNode)
    {
        return new JaxbXmlPath(mRootNode, inJaxbNode, null);
    }

    /**
     * Returns a path for passed field within the passed JAXB node.
     * <p>
     * Note that the passed field can be either a JAXB element or a simple field (Integer/Enum/etc).
     */
    public JaxbXmlPath forNode(Object inJaxbParentNode, Object inField)
    {
        return new JaxbXmlPath(mRootNode, inJaxbParentNode, inField);
    }

    /**
     * Obtain a string representation of this XML path.
     * <p>
     * Note that if forNode() hasn't been called, the path is assumed to apply to the root node.
     *
     * @return null if the node does not exist in the tree. While this is unlikely (caller error), it is better for the caller to
     *              have an error message with no path, than to have a runtime thrown from here (losing caller's error message).
     *              A null path indicates that a fix is required in the caller (to pass a child/descendant of the root node).
     */
    @Override
    public String toString() throws IllegalStateException
    {
        if (mJaxbParentNode == null)
        {
            Object rootNodeValue = mRootNode;

            if (rootNodeValue instanceof JAXBElement)
                rootNodeValue = ((JAXBElement<?>) rootNodeValue).getValue();

            return buildPathFromRoot(mRootNode, rootNodeValue);
        }

        return buildPathToField(mRootNode, mJaxbParentNode, mFieldValue);
    }

    /**
     * Build a path from the inRootNode to the inNodeToFind, and from there to the inFieldToFind.
     * <p>
     * While it might seem like a good idea to just search for the inFieldToFind directly, this approach would have potential
     * issues with finding the wrong path if a value was referenced in two or more spots in the tree. For example, if the jaxb
     * parsing was to use Integer.valueOf(), we could find the same Integer instance referred to from different parts of the tree
     * (and get the path very wrong). Another example would be an enum which is referred to from multiple places in the tree (or
     * from the same place in an ancestor that is repeatable). It is still possible to get this problem while using the
     * inNodeToFind approach (an element contains two Integers or two enums - with the same value), but much less likely (and the
     * path will be close).
     */
    private static String buildPathToField(Object inRootNode,
                                           Object inNodeToFind,
                                           @Nullable Object inFieldToFind)
    {
        String path = buildPathFromRoot(inRootNode, inNodeToFind);

        if (path == null || inFieldToFind == null)
            return path;

        String fieldPath = buildPath(inNodeToFind, inFieldToFind);

        return fieldPath == null ? path : (path + '/' + fieldPath);
    }

    /**
     * Wrapper for buildPath() which requires the passed node to be a root node.
     */
    private static String buildPathFromRoot(Object inRootNode, Object inNodeToFind)
    {
        // Ensure that inNodeToFind is an xml node (non-enum) or a collection.
        // - The below code ensures that we aren't passed a simple data type.
        if (!(inNodeToFind instanceof Collection<?>) && !isXmlNode(inNodeToFind))
            throw new IllegalStateException("Unexpected attempt to find non-xml node " + inNodeToFind.getClass().getName() +
                                            " within xml tree for root node " + inRootNode.getClass().getName());

        String rootNodeName = null;

        if (inRootNode instanceof JAXBElement)
        {
            JAXBElement<?> jaxbElem = (JAXBElement<?>) inRootNode;
            rootNodeName = jaxbElem.getName().getLocalPart();

            if (jaxbElem.getValue() == inNodeToFind)
                return rootNodeName;

            if (!isXmlNode(jaxbElem))
                return null;

            String path = buildPath(jaxbElem, inNodeToFind);

            return path == null ? null : (rootNodeName + '/' + path);
        }

        XmlRootElement xmlRootElem = inRootNode.getClass().getAnnotation(XmlRootElement.class);

        if (xmlRootElem == null)
            throw new IllegalStateException("Passed root node [" + inRootNode.getClass().getName() +
                                            "] must be a jaxb defined root node");
        rootNodeName = xmlRootElem.name();

        if (inRootNode == inNodeToFind)
            return rootNodeName;

        String path = buildPath(inRootNode, inNodeToFind);

        return path == null ? null : (rootNodeName + '/' + path);
    }

    /**
     * Return a path from the inParentNode to the inNodeToFind, or null if the node cannot be found.
     * <p>
     * Note that this method is recursive. The path returned never includes the tag for the parent node - this is always added
     * by the caller.
     * <p>
     * Caller must ensure that:
     * <ul>
     *   <li>inNodeToFind is not the same as inParentNode (instance/reference check).</li>
     *   <li>inParentNode must be an xml node (see {@link #isXmlNode(Object)}).</li>
     * </ul>
     */
    private static String buildPath(Object inParentNode, Object inNodeToFind)
    {
        // For JAXElement objects we must use the value to construct the path
        // - Caller should ensure the correct element name is used.
        if (inParentNode instanceof JAXBElement<?>)
        {
            Object jaxbElemValue = ((JAXBElement<?>) inParentNode).getValue();

            return buildPath(jaxbElemValue, jaxbElemValue.getClass(), inNodeToFind);
        }

        return buildPath(inParentNode, inParentNode.getClass(), inNodeToFind);
    }

    /**
     * Return a path from the inParentNode to the inNodeToFind, or null if the node cannot be found.
     * <p>
     * Note that this method is recursive. The path returned never includes the tag for the parent node - this is always added
     * by the caller.
     * <p>
     * Caller must ensure that:
     * <ul>
     *   <li>inNodeToFind is not the same as inParentNode (instance/reference check).</li>
     *   <li>inClazz must be the class or superclass of inParentNode</li>
     *   <li>inParentNode must be an xml node (see {@link #isXmlNode(Object)}).</li>
     * </ul>
     */
    private static String buildPath(Object inParentNode, Class<?> inClazz, Object inNodeToFind)
    {
        Field[] fields = inClazz.getDeclaredFields();
        AccessibleObject.setAccessible(fields, true);

        for (Field field : fields)
        {
            boolean isAttribute = false;
            String fieldName;
            XmlAttribute xmlAttr = field.getAnnotation(XmlAttribute.class);
            XmlElement[] multiXmlElems = null;

            if (xmlAttr == null)  // Not an attribute - see if field is an element.
            {
                XmlElement xmlElem = field.getAnnotation(XmlElement.class);

                if (xmlElem == null)  // May be an element with no annotation - first see if field has multiple types of element.
                {
                    XmlElements xmlElems = field.getAnnotation(XmlElements.class);

                    if (xmlElems == null)  // Not an attribute or an XmlElements - treat as element (with no annotation present).
                    {
                        fieldName = null;
                    }
                    else
                    {
                        multiXmlElems = xmlElems.value();
                        fieldName = multiXmlElems[0].name();  // Default to name of first element (update later).
                    }
                }
                else
                {
                    fieldName = xmlElem.name();   // Annotation present - name() returns "##default" if name is not present.
                }
            }
            else
            {
                fieldName = xmlAttr.name();   // Annotation present - name() returns "##default" if name is not present.
                isAttribute = true;
            }

            // The XmlElement annotation can be missing (or not have a "name" field) if the field in the schema starts with a
            // lower-case letter instead of an upper-case letter. For example, Amadeus schemas do this.
            if (fieldName == null || fieldName.equals("##default"))
                fieldName = field.getName();

            Object fieldValue;
            try
            {
                fieldValue = field.get(inParentNode);
            }
            catch (IllegalAccessException iae)
            {
                throw new IllegalStateException("Cannot access field value for field " + fieldName + " in " +
                                                inParentNode.getClass().getName(), iae);
            }

            if (fieldValue instanceof JAXBElement<?>)
            {
                JAXBElement<?> jaxbElem = (JAXBElement<?>) fieldValue;
                fieldName = jaxbElem.getName().getLocalPart();

                if (jaxbElem.getValue() == inNodeToFind)
                    return fieldName;
            }

            if (fieldValue == inNodeToFind)
                return isAttribute ? "@" + fieldName : fieldName;

            if (isAttribute || fieldValue == null)
                continue;

            if (fieldValue instanceof Collection<?>)
            {
                Collection<?> collection = (Collection<?>) fieldValue;
                int elemOccurrence = 0;

                for (Object obj : collection)
                {
                    elemOccurrence++;

                    if (obj == null)
                        throw new IllegalStateException("Unexpected null element in collection for field " + fieldName +
                                                        " in " + inParentNode.getClass().getName());

                    if (obj == inNodeToFind)
                        return calcCollectionFieldName(fieldName, elemOccurrence, obj, collection, multiXmlElems);

                    if (isXmlNode(obj))
                    {
                        String path = buildPath(obj, inNodeToFind);

                        if (path != null)
                            return calcCollectionFieldName(fieldName, elemOccurrence, obj, collection, multiXmlElems) + '/' + path;
                    }
                }
            }
            else if (isXmlNode(fieldValue))
            {
                String path = buildPath(fieldValue, inNodeToFind);

                if (path != null)
                    return fieldName + '/' + path;
            }
        }

        Class<?> superClazz = inClazz.getSuperclass();

        // Should never need to look at Object fields here
        if (superClazz != null && !superClazz.equals(Object.class))
            return buildPath(inParentNode, superClazz, inNodeToFind);

        return null;
    }

    /**
     * Calculate the correct field name (including occurrence) for an element in a collection.
     * <p>
     * Where the container field does not contain multiple types of xml elements, this is easy - just return the default field name
     * with the passed occurrence.
     * <p>
     * Otherwise (where the container field does contain multiple types of xml elements), use the class of the passed element to
     * determine the correct element name. The element occurrence is found by looking at elements of the same type within the
     * collection.
     */
    private static String calcCollectionFieldName(String inDefaultFieldName,
                                                  int inElementOccurrence,
                                                  Object inElementWithinCollection,
                                                  Collection<?> inCollection,
                                                  @Nullable XmlElement[] inMultiXmlElems)   throws IllegalStateException
    {
        if (inMultiXmlElems == null)
            return inDefaultFieldName + '[' + inElementOccurrence + ']';

        // Example:
        //      @XmlElements({
        //          @XmlElement(name = "Flight", type = Flight.class),
        //          @XmlElement(name = "ARNK", type = ARNK.class)
        //      })
        //      protected List<Object> flightOrARNK;
        Class<? extends Object> elemClass = inElementWithinCollection.getClass();
        int elementOccurrence = 0;

        for (Object elem : inCollection)
        {
            if (elem.getClass() == elemClass)
                elementOccurrence++;

            if (elem == inElementWithinCollection)
                break;
        }

        for (XmlElement xmlElem : inMultiXmlElems)
        {
            if (xmlElem.type() == elemClass)
                return xmlElem.name() + '[' + elementOccurrence + ']';
        }

        StringBuilder errMsg = new StringBuilder("Unable to determine field name for " + elemClass.getName() + " in:");

        for (XmlElement xmlElem : inMultiXmlElems)
            errMsg.append("\n  ").append(xmlElem.name()).append(": ").append(xmlElem.type().getName());

        throw new IllegalStateException(errMsg.toString());
    }

    /**
     * Determine if the passed object represents a jaxb annotated xml node.
     * <p>
     * For example, returns {@code false} for String/Integer/etc, but {@code true} for RecordCT/etc. This method also returns false
     * for enumeration values (even though these are xml-annotated) as we want to treat enums as simple types (not traversing into
     * them and not expecting them to be passed as a jaxb node).
     * <p>
     * Note that this can be {@code true} for a JAXBElement.
     */
    private static boolean isXmlNode(Object inNode)
    {
        Class<?> nodeClass = inNode.getClass();

        if (inNode instanceof JAXBElement)
        {
            Object jaxbElemValue = ((JAXBElement<?>) inNode).getValue();

            if (jaxbElemValue == null)
                return false;

            nodeClass = jaxbElemValue.getClass();
        }

        return nodeClass.isAnnotationPresent(XmlType.class) && !nodeClass.isEnum();
    }
}