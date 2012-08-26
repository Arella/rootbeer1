/* 
 * Copyright 2012 Phil Pratt-Szeliga and other contributors
 * http://chirrup.org/
 * 
 * See the file LICENSE for copying permission.
 */

package edu.syr.pcpratts.rootbeer.classloader;

import edu.syr.pcpratts.rootbeer.util.SystemOutHandler;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import soot.Value;
import soot.ValueBox;
import soot.jimple.InvokeExpr;
import java.io.*;
import java.util.*;
import java.util.logging.*;
import soot.*;
import soot.jimple.Stmt;
import soot.util.Chain;

public class FastWholeProgram {

  private final List<String> m_paths;
  private final List<String> m_classPaths;
  private final String m_tempFolder;
  private final Map<String, SootClass> m_classes;
  private final Collection<SootMethod> m_entryMethods;
  private FastCallGraph m_callGraph;
  private final FastClassResolver m_resolver;
  private List<String> m_applicationClasses;
  private List<String> m_cgWorkQueue;
  private Set<String> m_cgVisited;
  private List<String> m_bodiesClasses;
  private final Logger m_log;
  private List<String> m_ignorePackages;
  private List<String> m_keepPackages;
  private List<String> m_runtimeClasses;
  private Set<String> m_resolvedMethods;

  public FastWholeProgram() {
    m_log = Logger.getLogger("edu.syr.pcpratts");
    showAllLogging();
    m_paths = new ArrayList<String>();
    m_classPaths = new ArrayList<String>();
    m_entryMethods = new HashSet<SootMethod>(); //faster lookups
    m_classes = new HashMap<String, SootClass>();

    m_tempFolder = "jar-contents";
    File file = new File(m_tempFolder);
    file.mkdirs();

    m_resolver = new FastClassResolver(m_tempFolder, m_classPaths);

    m_applicationClasses = new ArrayList<String>();
    
    m_ignorePackages = new ArrayList<String>();
    m_ignorePackages.add("edu.syr.pcpratts.compressor.");
    m_ignorePackages.add("edu.syr.pcpratts.deadmethods.");
    m_ignorePackages.add("edu.syr.pcpratts.jpp.");
    m_ignorePackages.add("edu.syr.pcpratts.rootbeer.");
    m_ignorePackages.add("pack.");
    m_ignorePackages.add("jasmin.");
    m_ignorePackages.add("soot.");
    m_ignorePackages.add("beaver.");
    m_ignorePackages.add("polyglot.");
    m_ignorePackages.add("org.antlr.");
    m_ignorePackages.add("java_cup.");
    m_ignorePackages.add("ppg.");
    m_ignorePackages.add("antlr.");
    m_ignorePackages.add("jas.");
    m_ignorePackages.add("scm.");  
    
    m_keepPackages = new ArrayList<String>();
    m_keepPackages.add("edu.syr.pcpratts.rootbeer.testcases.");
    m_keepPackages.add("edu.syr.pcpratts.rootbeer.runtime.remap.");
    
    m_runtimeClasses = new ArrayList<String>();
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.generate.bytecode.Constants");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.runtime.RootbeerFactory");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.runtime.Rootbeer");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.runtime.RootbeerGpu");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.runtime.Kernel");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.runtime.CompiledKernel");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.runtime.Serializer");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.runtime.memory.Memory");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.runtime.Sentinal");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.test.TestSerialization");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.test.TestSerializationFactory");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.test.TestException");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.test.TestExceptionFactory");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.runtime.util.Stopwatch");
    m_runtimeClasses.add("edu.syr.pcpratts.rootbeer.runtime.PrivateFields");
    
    m_resolvedMethods = new HashSet<String>();
  }

  /**
   * Adds a path of source to be analyzed
   *
   * @param path the path
   */
  public void addPath(String path) {
    m_paths.add(normalizePath(path));
  }

  private String normalizePath(String path) {
    File file = new File(path);
    if(file.isDirectory() == false){
      return path;
    }
    if (path.endsWith(File.separator) == false) {
      path += File.separator;
    }
    return path;
  }

  /**
   * Add many paths to analyze
   *
   * @param paths the paths to analyze. No nulls allowed.
   */
  public void addPath(Collection<String> paths) {
    List<String> list_paths = new ArrayList<String>();
    list_paths.addAll(paths);
    for (int i = 0; i < list_paths.size(); ++i) {
      list_paths.set(i, normalizePath(list_paths.get(i)));
    }
    m_paths.addAll(paths);
  }

  /**
   * Adds one class path entry
   *
   * @param path
   */
  public void addClassPath(String path) {
    m_classPaths.add(path);
  }

  /**
   * Adds many class path entries
   *
   * @param path
   */
  public void addClassPath(Collection<String> paths) {
    m_classPaths.addAll(paths);
  }

  /**
   * Adds all the jars in the directory to the class path, recursively
   * @param folder 
   */
  public void addClassPathDir(String folder) {
    File file = new File(folder);
    if (file.exists() == false) {
      throw new RuntimeException("folder: " + folder + " does not exist");
    }
    File[] children = file.listFiles();
    for (File child : children) {
      if (child.isDirectory()) {
        addClassPathDir(child.getAbsolutePath());
      } else {
        String name = child.getName();
        if (name.startsWith(".")) {
          continue;
        }
        if (name.endsWith(".jar") == false) {
          continue;
        }
        addClassPath(child.getAbsolutePath());
      }
    }
  }
  
  public void init() {
    try {
      for (String path : m_paths) {
        File file = new File(path);
        if (file.isDirectory()) {
          extractPath(path);
        } else {
          extractJar(path);
        }
      }
      m_resolver.cachePackageNames();
      loadToSignatures();
    } catch(Exception ex){
      ex.printStackTrace();
    }
  }
  
  private void loadToBodies() {
    m_callGraph = new FastCallGraph();
    m_bodiesClasses = new ArrayList<String>();
    m_bodiesClasses.addAll(m_applicationClasses);
    m_bodiesClasses.addAll(m_runtimeClasses);
    for (SootMethod entry : m_entryMethods) {
      String class_name = entry.getDeclaringClass().getName();
      if (m_bodiesClasses.contains(class_name) == false) {
        m_bodiesClasses.add(class_name);
      }
    }
    m_resolver.setBodiesClasses(m_bodiesClasses);
    m_cgWorkQueue = new LinkedList<String>();
    m_cgVisited = new HashSet<String>();

    for(String soot_class_str : m_bodiesClasses){
      SootClass soot_class = Scene.v().getSootClass(soot_class_str);
      for(SootMethod method : soot_class.getMethods()){
        m_cgWorkQueue.add(method.getSignature());
      }
    }
    while (m_cgWorkQueue.isEmpty() == false) {
      String curr_signature = m_cgWorkQueue.get(0);
      m_cgWorkQueue.remove(0);
      if (m_cgVisited.contains(curr_signature)) {
        continue;
      }
      m_cgVisited.add(curr_signature);
      loadToBody(curr_signature);
    }
  }

  private SootMethod fullyResolveMethod(String method_signature){
    final String soot_class_str = SignatureUtil.classFromMethodSig(method_signature);
    if(m_resolvedMethods.contains(method_signature)){
      return m_resolver.resolveMethod(method_signature);
    }
    m_resolvedMethods.add(method_signature);
    m_log.log(Level.FINER, "Resolving to bodies:  "+soot_class_str);
    m_resolver.resolveClass(soot_class_str, SootClass.BODIES);
    SootMethod curr = m_resolver.resolveMethod(method_signature);
    if (curr == null || curr.isConcrete() == false) {
      m_log.log(Level.FINER, "Unable to find a concrete body for "+soot_class_str);
      return null;
    } //hack to make sure it really loads it all
    SootClass actual_class = curr.getDeclaringClass();
    m_resolver.forceResolveClass(actual_class.getName(), SootClass.BODIES);
    return curr;
  }
  
  private void loadToBody(String curr_signature) {
    SootMethod curr = fullyResolveMethod(curr_signature);
    if(curr == null){
      return;
    }
    m_log.log(Level.FINEST, "call graph dfs: "+curr.getSignature());

  //For some weird reason,  curr.hasActiveBody() returns false, even though 
  //curr.retrieveActiveBody() returns a body... go figure
    Body body = null;
    try{
      body = curr.retrieveActiveBody();
    } catch (RuntimeException e){
      m_log.log(Level.FINER, "cannot find body for method: "+curr.getSignature());
      return;
    }
    
    //Search for all invokes and add them to the call graph
    for (Unit curr_unit : body.getUnits()){
      for (ValueBox box : (List<ValueBox>)curr_unit.getUseAndDefBoxes()) {
        final Value value = box.getValue();
        if (value instanceof InvokeExpr) {
          final InvokeExpr expr = (InvokeExpr) value;
          try {
            final SootMethodRef method_ref = expr.getMethodRef();
            final String class_name = method_ref.declaringClass().getName();
            if (m_bodiesClasses.contains(class_name) == false) {
              m_bodiesClasses.add(class_name);
            }
            final String dest_sig = method_ref.getSignature();
            SootMethod dest_method = fullyResolveMethod(dest_sig);
            if(dest_method == null){
              continue;
            }
            
            try {
              Body dest_body = dest_method.retrieveActiveBody();
            } catch(Exception ex){
              continue;
            }
            
            //m_log.log(Level.FINEST, "Adding edge: {0} to {1}", new Object[]{curr.getSignature(), dest_sig});
            //m_callGraph.addEdge(curr.getSignature(), dest_sig, (Stmt) curr_unit);
            m_cgWorkQueue.add(dest_sig);
          } catch (Exception ex) {
            m_log.log(Level.WARNING, "Exception while recording call", ex);
          }
        }
      }
    }
  }

  private void extractPath(String path) throws Exception {
    File file = new File(path);
    File[] children = file.listFiles();
    for (File child : children) {
      extractPath(path, child.getName());
    }
  }

  private void extractPath(String root, String subfolder) throws Exception {
    String full_name = root + File.separator + subfolder;
    File file = new File(full_name);
    if (file.isDirectory()) {
      file.mkdirs();
      File dest = new File(m_tempFolder + File.separator + subfolder);
      dest.mkdirs();
      File[] children = file.listFiles();
      for (File child : children) {
        extractPath(root, subfolder + File.separator + child.getName());
      }
    } else {
      String class_name = filenameToClass(File.separator + subfolder);
      m_applicationClasses.add(class_name);
      FileInputStream fin = new FileInputStream(full_name);
      FileOutputStream fout = new FileOutputStream(m_tempFolder + File.separator + subfolder);
      WriteStream writer = new WriteStream();
      writer.write(fin, fout);
      fin.close();
      fout.close();
    }
  }

  private void extractJar(String path) {
    File pathFile = new File(path);
    try {
      JarInputStream is = new JarInputStream(new FileInputStream(pathFile));
      while (true) {
        JarEntry entry = is.getNextJarEntry();
        if (entry == null) {
          break;
        }
        if(entry.isDirectory() == false){
          String class_name = filenameToClass(entry.getName());
          if(ignorePackage(class_name) == false){
            m_applicationClasses.add(class_name);
          }
        }
        WriteJarEntry writer = new WriteJarEntry();
        writer.write(entry, is, m_tempFolder);
      }
      is.close();
    } catch (Exception ex) {
      m_log.log(Level.WARNING, "Unable to extract Jar", ex);
    }
  }

  private void loadToSignatures() throws Exception {
    File root = new File(m_tempFolder);
    loadToSignatures(root);
  }

  private void addBasicClass(String class_name) {
    addBasicClass(class_name, SootClass.HIERARCHY);
  }

  private void addBasicClass(String class_name, int level) {
    m_resolver.resolveClass(class_name, level);
  }

  private void loadBuiltIns() {
    addBasicClass("java.lang.Object");
    addBasicClass("java.lang.Class", SootClass.SIGNATURES);

    addBasicClass("java.lang.Void", SootClass.SIGNATURES);
    addBasicClass("java.lang.Boolean", SootClass.SIGNATURES);
    addBasicClass("java.lang.Byte", SootClass.SIGNATURES);
    addBasicClass("java.lang.Character", SootClass.SIGNATURES);
    addBasicClass("java.lang.Short", SootClass.SIGNATURES);
    addBasicClass("java.lang.Integer", SootClass.SIGNATURES);
    addBasicClass("java.lang.Long", SootClass.SIGNATURES);
    addBasicClass("java.lang.Float", SootClass.SIGNATURES);
    addBasicClass("java.lang.Double", SootClass.SIGNATURES);

    addBasicClass("java.lang.String");
    addBasicClass("java.lang.StringBuffer", SootClass.SIGNATURES);

    addBasicClass("java.lang.Error");
    addBasicClass("java.lang.AssertionError", SootClass.SIGNATURES);
    addBasicClass("java.lang.Throwable", SootClass.SIGNATURES);
    addBasicClass("java.lang.NoClassDefFoundError", SootClass.SIGNATURES);
    addBasicClass("java.lang.ExceptionInInitializerError");
    addBasicClass("java.lang.RuntimeException");
    addBasicClass("java.lang.ClassNotFoundException");
    addBasicClass("java.lang.ArithmeticException");
    addBasicClass("java.lang.ArrayStoreException");
    addBasicClass("java.lang.ClassCastException");
    addBasicClass("java.lang.IllegalMonitorStateException");
    addBasicClass("java.lang.IndexOutOfBoundsException");
    addBasicClass("java.lang.ArrayIndexOutOfBoundsException");
    addBasicClass("java.lang.NegativeArraySizeException");
    addBasicClass("java.lang.NullPointerException");
    addBasicClass("java.lang.InstantiationError");
    addBasicClass("java.lang.InternalError");
    addBasicClass("java.lang.OutOfMemoryError");
    addBasicClass("java.lang.StackOverflowError");
    addBasicClass("java.lang.UnknownError");
    addBasicClass("java.lang.ThreadDeath");
    addBasicClass("java.lang.ClassCircularityError");
    addBasicClass("java.lang.ClassFormatError");
    addBasicClass("java.lang.IllegalAccessError");
    addBasicClass("java.lang.IncompatibleClassChangeError");
    addBasicClass("java.lang.LinkageError");
    addBasicClass("java.lang.VerifyError");
    addBasicClass("java.lang.NoSuchFieldError");
    addBasicClass("java.lang.AbstractMethodError");
    addBasicClass("java.lang.NoSuchMethodError");
    addBasicClass("java.lang.UnsatisfiedLinkError");

    addBasicClass("java.lang.Thread");
    addBasicClass("java.lang.Runnable");
    addBasicClass("java.lang.Cloneable");

    addBasicClass("java.io.Serializable");

    addBasicClass("java.lang.ref.Finalizer");
  }

  private void loadToSignatures(File file) throws Exception {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      for (File child : children) {
        loadToSignatures(child);
      }
      return;
    }
    String name = file.getAbsolutePath();
    if (name.endsWith(".class") == false) {
      return;
    }
    File full_temp_folder = new File(m_tempFolder);
    name = name.substring(full_temp_folder.getAbsolutePath().length());
    if (isFilename(name)) {
      name = filenameToClass(name);
    }
    if (m_applicationClasses.contains(name) == false) {
      return;
    }
    m_log.log(Level.FINE, "Loading class to signature: "+name);

    SootClass loaded = m_resolver.resolveClass(name, SootClass.SIGNATURES);
    m_classes.put(name, loaded);
  }

  private boolean isFilename(String name) {
    if (name.endsWith(".class")) {
      return true;
    }
    return false;
  }

  private String filenameToClass(String name) {
    String sep = "/";
    if (name.startsWith(sep)) {
      name = name.substring(sep.length());
    }
    name = name.replace(".class", "");
    name = name.replace(sep, ".");
    return name;
  }

  private void detectEntryPoints() {
    for (SootClass sc : m_classes.values()) {
      Chain<SootClass> ifaces = sc.getInterfaces();
      Iterator<SootClass> iter = ifaces.iterator();
      while(iter.hasNext()){
        SootClass curr = iter.next();
        if(curr.toString().equals("edu.syr.pcpratts.rootbeer.runtime.Kernel")){
          SootMethod entry = sc.getMethodByName("gpuMethod");
          m_entryMethods.add(entry);
          break;
        }
      }
    }
  }
  
  /**
   * Enables full logging. Logging will be sent to System.out.
   */
  public void showAllLogging() {
    Logger parent = Logger.getLogger(this.getClass().getPackage().getName());
    parent.setLevel(Level.ALL);
    Handler handler = new SystemOutHandler();
    m_log.setUseParentHandlers(true);
    m_log.setLevel(Level.ALL);
    m_log.addHandler(handler);
  }

  /**
   * Detects constructors and static initializers and adds them to the entry points.
   * @param sc the soot class object
   */
  private void addConstructorsToEntryPoints(SootClass sc) {
    m_entryMethods.addAll(DetectionUtils.findConstructors(sc));
    m_entryMethods.addAll(DetectionUtils.findStaticInitializers(sc));
  }
  
  public List<String> findKernelClasses() {
    detectEntryPoints();
    loadBuiltIns();
    loadToBodies();
    
    for (SootClass sc : m_classes.values()){
      if (sc.resolvingLevel() == SootClass.BODIES){
        addConstructorsToEntryPoints(sc);
      }
    }
    
    List<String> ret = new ArrayList<String>();
    for(SootMethod method : m_entryMethods){
      String soot_class = method.getDeclaringClass().toString();
      if(ret.contains(soot_class) == false){
        ret.add(soot_class);
      }
    }
    return ret;
  }

  public List<String> getApplicationClasses() {
    return m_applicationClasses;
  }

  private boolean ignorePackage(String class_name) {
    for(String runtime_class : m_runtimeClasses){
      if(class_name.equals(runtime_class)){
        return false;
      }
    }
    for(String keep_package : m_keepPackages){
      if(class_name.startsWith(keep_package)){
        return false;
      }
    }
    for(String ignore_package : m_ignorePackages){
      if(class_name.startsWith(ignore_package)){
        return true;
      }
    }
    return false;
  }
}