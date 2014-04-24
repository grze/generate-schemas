import com.eucalyptus.component.ComponentId
import com.eucalyptus.component.annotation.ComponentMessage
import com.eucalyptus.system.Ats
import com.google.common.base.Predicate
import com.google.common.collect.HashMultimap
import com.google.common.collect.Sets
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import edu.ucsb.eucalyptus.msgs.BaseMessage
import javassist.ClassPool
import javassist.CtClass
import javassist.NotFoundException
import org.jibx.runtime.BindingDirectory
import org.jibx.runtime.IBindingFactory

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.jar.JarFile

//NOTHING GOES ABOVE log
boolean lameDebugFlag = System.getenv().containsKey("DEBUG") ? true : false;
boolean lameVerboseFlag = System.getenv().containsKey("VERBOSE") || lameDebugFlag ? true : false;
def log = { Object o, boolean eol = true ->
  if (lameVerboseFlag && !eol) {
    print o
  } else if (lameVerboseFlag && eol) {
    println o
  }
}
def ClassPool pool = ClassPool.getDefault();
def baseMsgCt = pool.get(edu.ucsb.eucalyptus.msgs.BaseMessage.class.getCanonicalName());
def stringCtClass = { String key ->
  try {
    return pool.getCtClass(key);
  } catch (final NotFoundException e) {
    return baseMsgCt;
  }
}
def File bindingCacheLocation = new File(args[0]).getCanonicalFile();
def File generatorLocation = new File(this.class.getProtectionDomain().getCodeSource().getLocation().getFile()).getParentFile();
log "Using ${bindingCacheLocation}"



def urls = (ClassLoader.getSystemClassLoader() as URLClassLoader).getURLs()
def classSources = urls.collect { URL u ->
  new File(u.getFile())
}.findAll {
  !it.getAbsolutePath().startsWith(generatorLocation.getAbsolutePath())
}.collect { File f -> log "Adding ${f}"; f }

/*read all those places*/
def ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
Runtime.getRuntime().addShutdownHook({ executor.shutdownNow() } as Thread)
def readJarFile = { File f ->
  def jar
  try {
    def ret = Collections.list((jar = new JarFile(f)).entries()).findAll {
      it.getName().matches(".*\\.class.{0,1}")
    }.collect {
      it.getName().replaceAll("/", ".").replaceAll("\\.class.{0,1}", "")
    }
    log "Read: ${ret.size()} classes from ${f.getAbsolutePath()}"
    return ret
  } finally {
    jar?.close();
  }
}
def s = System.currentTimeMillis()
def readerFutures = executor.invokeAll(classSources.collect { File f ->
  ({ return readJarFile(f) }) as Callable<List<String>>
})
def jarFileClassLists = readerFutures.collect { it.get() }.flatten { it }
println "Read ${jarFileClassLists.size()} classes from ${readerFutures.size()} sources in ${System.currentTimeMillis() - s} milliseconds"

def ctMessageTypes = jarFileClassLists.collect(stringCtClass).inject([]) {
  acc, CtClass ct ->
    try {
      if (ct.subtypeOf(baseMsgCt) && !baseMsgCt.equals(ct)) {
        try {
          ct.getDeclaredField(BindingDirectory.BINDINGLIST_NAME);
          acc.add(ct)
          System.out.printf(".")
        } catch (NotFoundException ex) {
          System.out.printf("[${ct.getSimpleName()}]")
        }
      }
    } catch (Exception e) {
    }
    acc
}
def messageClasses = ctMessageTypes.collect({ CtClass ct -> ClassLoader.getSystemClassLoader().loadClass(ct.getName()) }).collectNested {
  it
}.findAll { Class<? extends BaseMessage> c ->
  Ats.inClassHierarchy(c).has(ComponentMessage.class)
}
def componentTypes = messageClasses.collect { Ats.inClassHierarchy(it).get(ComponentMessage.class).value() }.unique()
println "\nFound ${ctMessageTypes.size()} message types for ${componentTypes.size()} distinct components."


def HashMultimap<Class<? extends ComponentId>, Class<? extends BaseMessage>> compMessages = HashMultimap.create();
def HashMultimap<Class<? extends ComponentId>, String> compNamespaces = HashMultimap.create();

componentTypes.each { Class<? extends ComponentId> c ->
  def cMessages = Sets.filter(messageClasses as Set, {
    Ats.inClassHierarchy(it).get(ComponentMessage.class).value() == c
  } as Predicate<Class<? extends BaseMessage>>)
  compMessages.putAll(c, cMessages)
  println "Found ${cMessages.size()} message types for component ${c.getSimpleName()}"
}

compMessages.entries().collect { Map.Entry<Class<? extends ComponentId>, Class<? extends BaseMessage>> e ->
  try {
    e.value.getDeclaredField(BindingDirectory.BINDINGLIST_NAME).get(null).split('\\|').findAll { String f -> f?.length() > 0 }.collect { String facName ->
      try {
        def facClass = ClassLoader.getSystemClassLoader().loadClass(facName);
        def IBindingFactory factory = facClass.getDeclaredMethod(BindingDirectory.FACTORY_INSTMETHOD, BindingDirectory.EMPTY_ARGS).invoke(null, null);
        println "${e.key.simpleName} ${e.value.simpleName} ${factory.getBindingName()} ${factory.getNamespaces().collect { ns -> "${ns}" } as Set}"
      } catch (Exception ex) {
        println "${e.key.simpleName} ${e.value.simpleName} ${facName} failed to load ${ex.getMessage()}"
      }
    }
  } catch (NoSuchFieldException ex) {
    println "Failed to find binding list field on: ${e.value} from ${e.value.protectionDomain.codeSource.location}"
  }
}

System.exit(0)
/*
Multimap<Class<? extends ComponentId>, Class> messageTypes = HashMultimap.create();
Multimap<Class<? extends ComponentId>, Class> complexTypes = HashMultimap.create();
Multimap<Class<? extends ComponentId>, Class> collectionTypes = HashMultimap.create();
Set<Class> failedTypes = Sets.newHashSet();

def componentFile = { Class<? extends ComponentId> compId -> new File("${compId.getSimpleName()}.wsdl") }
def writeComponentFile = { Class<? extends ComponentId> compId, String line ->
  if (lameDebugFlag) {
    log line
  }
  (componentFile(compId)).append("${line}\n");
}

xsTypeMap = [
'java.lang.String' : 'string',
'java.lang.Long'   : 'long',
'long'             : 'long',
'java.lang.Integer': 'int',
'int'              : 'int',
'java.lang.Boolean': 'boolean',
'boolean'          : 'boolean',
'java.lang.Double' : 'float',
'double'           : 'float',
'java.lang.Float'  : 'float',
'float'            : 'float',
]

class TypeTransforms {
  def fieldToGenericType = { Field f ->
    Type t = f.getGenericType();
    if (t != null && t instanceof ParameterizedType) {
      Type tv = ((ParameterizedType) t).getActualTypeArguments()[0];
      if (tv instanceof Class) {
        return ((Class) tv);
      }
    }
    return Object.class;
  }
}

class TypeFilters {
  def fieldNames = { Field f ->
    (!f.getName().startsWith("_")
    && !f.getName().startsWith("\$")
    && !f.getName().equals("metaClass")
    ) ? true : false;
  } as Predicate<Class>
  def messageType = { Class c ->
    BaseMessage.class.isAssignableFrom(c) && !BaseMessage.class.equals(c) && !c.toString().contains("\$")
  } as Predicate<Class>
  def primitiveType = { Type t -> (t?.isPrimitive() || Primitives.isWrapperType(t) || String.class.equals(t)) } as Predicate<Class>
  def primitives = { Field f -> fieldNames(f) && primitiveType(f?.getType()) } as Predicate<Class>
  def collections = { Field f -> fieldNames(f) && Collection.class.isAssignableFrom(f?.getType()) } as Predicate<Class>
  def complex = { Field f ->
    fieldNames(f) && !primitives(f) && !collections(f) && !(Class.class).isAssignableFrom(f?.getType())
  } as Predicate<Class>
}

class MessageFilters {
  def messageHasAnnotation = { Class c ->
    if (!Ats.inClassHierarchy(c).has(ComponentMessage.class)) {
      failedTypes.add(c);
      false
    } else {
      true
    }
  }

}

class TypeProcessors {
  def complexType
  def messageType

  {
    complexType = { Class compId, Class c ->
      println "${compId.simpleName}: ${c.simpleName}"
      complexTypes.put(compId, c);
      //TODO:GRZE fix the recursion issue here...
      c.getDeclaredFields().findAll(filterComplex).findAll { !it.getType().equals(c) }.each { Field f ->
        processComplexType(compId, (Class) f.getType());
      }
    }

    messageType = { Class<? extends ComponentId> compId, Class c ->
      messageTypes.put(compId, c);
      c.getDeclaredFields().findAll(filterComplex).each { Field f ->
        processComplexType(compId, f.getType());
      }
      c.getDeclaredFields().findAll(filterCollections).each { Field f ->
        Class fc = transformToGenericType(f);
        if (fc != null && !filterPrimitiveType(fc)) {
          collectionTypes.put(compId, fc);
          complexTypes.put(compId, fc);
        }
      }
    }
  }

}

//find all the message types
classList.findAll(filterMessageTypes).findAll(messageHasAnnotation).each { Class c ->
  ats = Ats.inClassHierarchy(c);
  ComponentMessage comp = ats.get(ComponentMessage.class);
  Class<? extends ComponentId> compId = comp.value();
  processMessageType(compId, c);
}

failedTypes.each { Class c ->
  //handle the unannotated {Walrus,Storage,etc.}ResponseType hierarchies by guessing their counterpart in the properly annotated hierarchy
  if (!Ats.inClassHierarchy(c).has(ComponentMessage.class) && c.getCanonicalName().endsWith("ResponseType")) {
    try {
      Class request = Class.forName(c.getCanonicalName().replaceAll("ResponseType\$", "Type"));
      ats = Ats.inClassHierarchy(request);
      ComponentMessage comp = ats.get(ComponentMessage.class);
      Class<? extends ComponentId> compId = comp.value();
      processMessageType(compId, c);
    } catch (Exception e) {
    }
  }
}


messageTypes.keySet().each { Class<? extends ComponentId> compId ->
  if (componentFile(compId).exists()) {
    File f = componentFile(compId);
    if (f.exists()) {
      f.delete();
    }
    f.write("""<?xml version="1.0" encoding="utf-8"?>\n""")
  }
}


String wsdlHeader = """<definitions targetNamespace="http://msgs.eucalyptus.com/" xmlns:xs="http://www.w3.org/2001/XMLSchema"
  xmlns:tns="http://msgs.eucalyptus.com/" xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/" xmlns="http://schemas.xmlsoap.org/wsdl/">
"""

def wsdlFooter = { Class<? extends ComponentId> compId ->
  """  <service name="${compId.getSimpleName()}">
    <port name="${compId.getSimpleName()}Port" binding="tns:${compId.getSimpleName()}Binding">
      <soap:address location="http://msgs.eucalyptus.com/" />
    </port>
  </service>
</definitions>""";
}

String schemaHeader = """  <types>
    <xs:schema xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:tns="http://msgs.eucalyptus.com/"
      targetNamespace="http://msgs.eucalyptus.com/" elementFormDefault="qualified">
"""

String schemaFooter = """    </xs:schema>
  </types>
"""

def renderSimpleField = { Field f ->
  """<xs:element name="${f.getName()}" type="xs:${
    xsTypeMap.get(f.getType().getCanonicalName())
  }" />"""
}

def renderComplexField = { Field f ->
  """<xs:element name="${f.getName()}" type="tns:${
    f.getType().getSimpleName()
  }Type" />"""
}

def renderCollectionField = { Field f ->
  """<xs:element name="${f.getName()}" type="tns:${
    transformToGenericType(f).getSimpleName()
  }SetType" minOccurs="0" />"""
}

def renderCollectionWrapper = { Class c ->
  """      <xs:complexType name="${c.getSimpleName()}SetType">
        <xs:sequence>
          <xs:element name="item" type="tns:${c.getSimpleName()}ItemType" minOccurs="0" maxOccurs="unbounded" />
        </xs:sequence>
      </xs:complexType>
"""
}

def transformFieldToQuery = { Field f -> f.getName().substring(0, 1).toUpperCase().concat(f.getName().substring(1)) }

def renderFieldReference = { Class c, Field f ->
  log("renderMessageType: ${c.getSimpleName()}.${f.getName()}", false)
  fats = Ats.from(f);
  PREFIX = "        "
  comment = ""
  if (fats.has(HttpParameterMapping.class)) {
    HttpParameterMapping mapping = fats.get(HttpParameterMapping.class);
    comment += "\n${PREFIX}<!-- Query: ${mapping.parameter()} -->\n"
  } else {
    comment += "\n${PREFIX}<!-- Query: ${transformFieldToQuery(f)} -->\n"
  }
  if (f == null) {
    return ""
  } else if (filterPrimitives(f)) {
    log " [primitive] ${f.getType()}"
    return "${comment}${PREFIX}${renderSimpleField(f)}"
  } else if (filterCollections(f)) {
    log " [collection] ${transformToGenericType(f)}"
    return "${comment}${PREFIX}${renderCollectionField(f)}"
  } else if (filterComplex(f)) {
    log " [complex] ${f.getType()}"
    return "${comment}${PREFIX}${renderComplexField(f)}"
  }
}



def renderComplexType = { Class c ->
  String header = """      <xs:complexType name="${c.getSimpleName()}Type">
        <xs:sequence>"""
  String footer = """
        </xs:sequence>
      </xs:complexType>
"""
  Classes.ancestors(c).each { Class a ->
    a.getDeclaredFields().findAll(filterFieldNames).each { Field f ->
      header += renderFieldReference(c, f)
    }
  }
  header + footer
}

def renderMessageType = { Class c ->
  String shortName = "${c.getSimpleName().replaceAll('Type$', '')}";
  String header = """      <xs:element name="${shortName}" type="tns:${c.getSimpleName()}" />
      <xs:complexType name="${c.getSimpleName()}">
        <xs:sequence>"""
  String footer = """
        </xs:sequence>
      </xs:complexType>
"""
  Classes.ancestors(c).each { Class a ->
    if (filterMessageTypes(a)) {
      a.getDeclaredFields().findAll(filterFieldNames).each { Field f ->
        header += renderFieldReference(c, f)
      }
    }
  }
  header + footer
}

def renderMessagePart = { Class c ->
  if (!c.getSimpleName().endsWith("ResponseType")) {
    """  <message name="${c.getSimpleName()}RequestMsg">
      <part name="${c.getSimpleName()}RequestMsgReq" element="tns:${c.getSimpleName()}" />
    </message>
    <message name="${c.getSimpleName()}ResponseMsg">
      <part name="${c.getSimpleName()}ResponseMsgResp" element="tns:${c.getSimpleName()}Response" />
    </message>
  """
  } else {
    ""
  }
}

def renderPortTypeHeader = { Class<? extends ComponentId> compId ->
  """  <portType name="${compId.getSimpleName()}PortType">"""
}

String portTypeFooter = "</portType>"

def renderPortTypeOperation = { Class c ->
  if (!c.getSimpleName().endsWith("ResponseType")) {
    """    <operation name="${c.getSimpleName()}">
      <input message="tns:${c.getSimpleName()}RequestMsg" />
      <output message="tns:${c.getSimpleName()}ResponseMsg" />
    </operation>
"""
  } else {
    ""
  }
}

def renderBindingHeader = { Class<? extends ComponentId> compId ->
  """  <binding name="${compId.getSimpleName()}Binding" type="tns:${compId.getSimpleName()}PortType">
    <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http" />
"""
}

def renderBindingOperation = { Class c ->
  """
    <operation name="${c.getSimpleName()}">
      <soap:operation soapAction="${c.getSimpleName()}" />
      <input>
        <soap:body use="literal" />
      </input>
      <output>
        <soap:body use="literal" />
      </output>
    </operation>
"""
}
String bindingFooter = "  </binding>"

def generateSchema = { Class<? extends ComponentId> compId ->
  writeComponentFile(compId, schemaHeader);
  complexTypes.get(compId).each { Class complexType ->
    log "generateSchema: ComplexType ${compId.getSimpleName()} ${complexType}"
    writeComponentFile(compId, renderComplexType(complexType));
  }
  collectionTypes.get(compId).each { Class collectionType ->
    log "generateSchema: Collection  ${compId.getSimpleName()} ${collectionType}"
    writeComponentFile(compId, renderCollectionWrapper(collectionType));
  }
  messageTypes.get(compId).each { Class msgType ->
    log "generateSchema: Message     ${compId.getSimpleName()} ${msgType}"
    writeComponentFile(compId, renderMessageType(msgType));
  }
  writeComponentFile(compId, schemaFooter);
}

def generateMessagePartsSection = { Class<? extends ComponentId> compId ->
  //Message Parts
  messageTypes.get(compId).each { Class msgType ->
    writeComponentFile(compId, renderMessagePart(msgType));
  }
}

def generatePortTypeSection = { Class<? extends ComponentId> compId ->
  //Port Type Operations
  writeComponentFile(compId, renderPortTypeHeader(compId));
  messageTypes.get(compId).each { Class msgType ->
    writeComponentFile(compId, renderPortTypeOperation(msgType));
  }
  writeComponentFile(compId, portTypeFooter);
}

def generateBindingSection = { Class<? extends ComponentId> compId ->
  //Binding section
  writeComponentFile(compId, renderBindingHeader(compId));
  messageTypes.get(compId).each { Class msgType ->
    writeComponentFile(compId, renderBindingOperation(msgType));
  }
  writeComponentFile(compId, bindingFooter);
}

//log generateSchema( ConfigurationService.class )
messageTypes.keySet().each { Class<? extends ComponentId> compId ->
  writeComponentFile(compId, wsdlHeader);
  generateSchema(compId);
  generateMessagePartsSection(compId);
  generatePortTypeSection(compId);
  generateBindingSection(compId);
  writeComponentFile(compId, wsdlFooter(compId));
}
failedTypes.findAll { !messageTypes.containsValue(it) }.each { Class c ->
  log "ERROR: Failed to find @ComponentMessage in class hierarchy for: ${c}";
}

messageTypes.keySet().each { Class<? extends ComponentId> compId ->
  File compFile = componentFile(compId);
  File tmpFile = File.createTempFile(compFile.getName(), null);
  //  tmpFile.deleteOnExit( );
  log "Moving ${compFile.getCanonicalPath()} to ${tmpFile.getCanonicalPath()}"
  Files.move(compFile, tmpFile);

  componentFile(compId).withPrintWriter { PrintWriter w ->
    log "Rewriting ${compFile.getCanonicalPath()}"
    w.write(tmpFile.text.replaceAll("(?m)^[ \t]*\r?\n", ""));
  }
}
*/
