package groovy.util;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

/**
 * Puente de compatibilidad para el plugin com.zeroc.gradle.ice-builder.slice 1.5.2.
 *
 * El plugin fue compilado contra Groovy 3 y usa groovy.util.XmlSlurper, clase
 * que Groovy 4 (incluido en Gradle 9) movió a groovy.xml.XmlSlurper. Sin este
 * puente la tarea compileSlice falla con NoClassDefFoundError. Gradle 9 es
 * necesario para poder compilar con JDK 25.
 */
public class XmlSlurper extends groovy.xml.XmlSlurper {

    public XmlSlurper() throws ParserConfigurationException, SAXException {
        super();
    }
}
