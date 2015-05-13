package in.sivareddy.graphparser.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.client.utils.URIBuilder;

import virtuoso.jena.driver.VirtGraph;
import virtuoso.jena.driver.VirtuosoQueryExecution;
import virtuoso.jena.driver.VirtuosoQueryExecutionFactory;
import virtuoso.jena.driver.VirtuosoUpdateFactory;
import virtuoso.jena.driver.VirtuosoUpdateRequest;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Sets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.shared.PrefixMapping;

/**
 * Tools for querying RDF Graphs
 *
 * @author Siva Reddy
 *
 */
public class RdfGraphTools {

  private VirtGraph virtGraph;
  private String httpUrl;
  private Integer timeOut = 0; // timeout in milli seconds
  private static String XSD_PREFIX =
      "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>";
  // ExecutorService executor = Executors.newFixedThreadPool(200);

  int nthreads = 50;
  final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(nthreads);
  ThreadPoolExecutor threadPool = new ThreadPoolExecutor(nthreads, nthreads,
      100, TimeUnit.SECONDS, queue);


  // stores query and its results
  private static Cache<String, Map<String, LinkedHashSet<String>>> queryCache =
      Caffeine.newBuilder().maximumSize(100000).build();

  private static Cache<String, List<Map<String, String>>> querySolutionCache =
      Caffeine.newBuilder().maximumSize(100000).build();

  public RdfGraphTools(String jdbcEndPoint, String username, String password) {
    this(jdbcEndPoint, null, username, password, 0);
  }

  public RdfGraphTools(String jdbcEndPoint, String username, String password,
      int timeOut) {
    this(jdbcEndPoint, null, username, password, timeOut);
  }

  public RdfGraphTools(String jdbcUrl, String httpUrl, String username,
      String password) {
    this(jdbcUrl, httpUrl, username, password, 0);
  }

  public RdfGraphTools(String jdbcUrl, String httpUrl, String username,
      String password, int timeOut) {
    virtGraph = new VirtGraph(null, jdbcUrl, username, password, true);

    PrefixMapping prefixMapping = virtGraph.getPrefixMapping();
    prefixMapping.setNsPrefix("xsd", "http://www.w3.org/2001/XMLSchema#");
    this.httpUrl = httpUrl;
    this.timeOut = timeOut;


    threadPool.setRejectedExecutionHandler(new RejectedExecutionHandler() {
      @Override
      public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        // this will block if the queue is full
        try {
          executor.getQueue().put(r);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    });
  }

  public List<Map<String, String>> runQueryJdbcSolutions(String query) {
    List<Map<String, String>> results = querySolutionCache.getIfPresent(query);
    if (results != null)
      return results;

    results = new ArrayList<>();
    QueryExecutionTaskJdbc task =
        new QueryExecutionTaskJdbc(virtGraph, query, results);

    Future<Void> future = threadPool.submit(task);
    // executor.submit(task);
    try {
      if (timeOut > 0) {
        future.get(timeOut, TimeUnit.MILLISECONDS);
      } else {
        future.get();
      }
    } catch (TimeoutException ex) {
      System.err.println("Timeout query: " + timeOut + ": " + query);
    } catch (Exception e) {
      // System.err.println("Other exception: " + timeOut + ": " + query);
      // Skip.
    } finally {
      task.close();
      querySolutionCache.put(query, results);
    }
    return results;
  }

  public List<Map<String, String>> runQueryHttpSolutions(String query) {
    List<Map<String, String>> results = querySolutionCache.getIfPresent(query);
    if (results != null)
      return results;

    results = new ArrayList<>();
    QueryExecutionTaskHttp task =
        new QueryExecutionTaskHttp(httpUrl, query, results);

    Future<Void> future = threadPool.submit(task);
    // executor.submit(task);
    try {
      if (timeOut > 0) {
        future.get(timeOut, TimeUnit.MILLISECONDS);
      } else {
        future.get();
      }
    } catch (TimeoutException ex) {
      System.err.println("Timeout query: " + timeOut + ": " + query);
    } catch (Exception e) {
      // e.printStackTrace();
      // System.err.println("Other exception: " + timeOut + ": " + query);
      // Skip.
    } finally {
      task.close();
      querySolutionCache.put(query, results);
    }
    return results;
  }

  public Map<String, LinkedHashSet<String>> runQueryJdbc(String query) {
    if (query == null) {
      return null;
    }

    Map<String, LinkedHashSet<String>> results = queryCache.getIfPresent(query);
    if (results != null)
      return results;

    List<Map<String, String>> solutions = runQueryJdbcSolutions(query);
    results = new HashMap<>();
    for (Map<String, String> solution : solutions) {
      for (String var : solution.keySet()) {
        results.putIfAbsent(var, new LinkedHashSet<>());
        results.get(var).add(solution.get(var).toString());
      }
    }

    queryCache.put(query, results);
    return results;
  }

  public Map<String, LinkedHashSet<String>> runQueryHttp(String query) {
    if (query == null) {
      return null;
    }

    Map<String, LinkedHashSet<String>> results = queryCache.getIfPresent(query);
    if (results != null)
      return results;

    List<Map<String, String>> solutions = runQueryHttpSolutions(query);
    results = new HashMap<>();
    for (Map<String, String> solution : solutions) {
      for (String var : solution.keySet()) {
        results.putIfAbsent(var, new LinkedHashSet<>());
        results.get(var).add(solution.get(var).toString());
      }
    }

    queryCache.put(query, results);
    return results;
  }

  public void insertIntoGraph(String graphURI, String s, String p, String o) {
    s = s.trim();
    p = p.trim();
    o = o.trim();
    String query =
        String
            .format(
                "INSERT INTO GRAPH <%s> { %s %s %s . } WHERE { FILTER NOT EXISTS {  %s %s %s . } }",
                graphURI, s, p, o, s, p, o);
    VirtuosoUpdateRequest vur = VirtuosoUpdateFactory.create(query, virtGraph);
    vur.exec();
  }

  public void deleteFromGraph(String graphURI, String s, String p, String o) {
    s = s.trim();
    p = p.trim();
    o = o.trim();
    String query =
        String
            .format(
                "DELETE FROM GRAPH <%s> { %s %s %s . } WHERE { FILTER EXISTS {  %s %s %s . } }",
                graphURI, s, p, o, s, p, o);
    VirtuosoUpdateRequest vur = VirtuosoUpdateFactory.create(query, virtGraph);
    vur.exec();
  }

  public static void getResults(ResultSet resultSet,
      Map<String, LinkedHashSet<String>> results) {
    if (resultSet == null) {
      return;
    }

    List<?> vars = resultSet.getResultVars();
    for (Object var : vars) {
      results.put(var.toString(), new LinkedHashSet<String>());
    }
    try {
      while (resultSet.hasNext()) {
        QuerySolution result = resultSet.nextSolution();
        for (Object var : vars) {
          String varString = var.toString();
          RDFNode value = result.get(varString);
          if (value != null) {
            results.get(varString).add(value.toString());
          }
        }
      }
    } catch (Exception e) {
      return;
    }
    return;
  }

  /**
   * Returns readable outuput of gold and predicted results that can be used for
   * evaluation with Berant's script.
   * 
   * @param goldResults
   * @param predResults
   * @return
   */
  public static Pair<Set<String>, Set<String>> getCleanedResults(
      Map<String, LinkedHashSet<String>> goldResults,
      Map<String, LinkedHashSet<String>> predResults) {

    Preconditions.checkArgument(goldResults != null,
        "Gold results should not be null");
    Preconditions.checkArgument(goldResults.keySet().size() <= 2,
        "Unknown target variable");
    String goldVar = null;
    String goldVarName = null;
    for (String key : goldResults.keySet()) {
      if (key.equals("targetValue")) {
        goldVarName = key;
      } else if (!key.contains("name")) {
        goldVar = key;
      }
    }

    boolean hasDate = false;
    LinkedHashSet<String> goldAnswers = goldResults.get(goldVarName);
    if (goldVarName != null && goldVarName.equals("targetValue")) {
      hasDate =
          (goldAnswers.size() > 0 && goldAnswers.iterator().next()
              .contains("XMLSchema#datetime")) ? true : false;
      if (hasDate) {
        goldAnswers = convertDatesToYears(goldAnswers);
      }
    } else {
      goldAnswers = goldResults.get(goldVar);
    }

    if (predResults == null || predResults.size() == 0)
      return Pair.of(goldAnswers, new LinkedHashSet<>());

    Preconditions.checkArgument(predResults.keySet().size() <= 2,
        "Unknown target variable");
    String predVar = null;
    String predVarName = null;
    for (String key : predResults.keySet()) {
      if (!key.contains("name")) {
        predVar = key;
      } else if (predResults.get(key).size() > 0) {
        predVarName = key;
      }
    }

    LinkedHashSet<String> predAnswersCleaned = new LinkedHashSet<>();
    if (goldVarName != null && goldVarName.equals("targetValue")) {
      LinkedHashSet<String> predAnswers =
          predVarName != null ? predResults.get(predVarName) : predResults
              .get(predVar);

      for (String predAnswer : predAnswers) {
        predAnswer = predAnswer.split("\\^\\^")[0];
        predAnswer = predAnswer.replaceAll("@[a-zA-Z\\-]+$", "");
        if (hasDate) {
          Matcher matcher = Pattern.compile("([0-9]{3,4})").matcher(predAnswer);
          if (matcher.find()) {
            predAnswer = matcher.group(1);
          }
        }
        predAnswersCleaned.add(predAnswer);
      }
    } else if (goldVar.equals("answerSubset") || goldVar.equals("answer")) {
      LinkedHashSet<String> predAnswers = predResults.get(predVar);
      for (String predAnswer : predAnswers) {
        boolean answerIsDate = predAnswer.contains("XMLSchema#datetime");
        predAnswer = predAnswer.split("\\^\\^")[0];
        String[] answers = predAnswer.split("/");
        predAnswer = answers[answers.length - 1];
        if (answerIsDate) {
          Matcher matcher = Pattern.compile("([0-9]{3,4})").matcher(predAnswer);
          if (matcher.find()) {
            predAnswer = matcher.group(1);
          }
        }
        predAnswersCleaned.add(predAnswer);
      }
    } else {
      predAnswersCleaned = predResults.get(predVar);
    }
    return Pair.of(goldAnswers, predAnswersCleaned);
  }

  /**
   * Useful in the case of questions with single variable
   *
   * @param goldResults
   * @param predResults
   * @return
   */
  public static boolean equalResults(
      Map<String, LinkedHashSet<String>> goldResults,
      Map<String, LinkedHashSet<String>> predResults) {
    Preconditions.checkArgument(goldResults != null,
        "Gold results should not be null");
    if (predResults == null || predResults.size() == 0) {
      return false;
    }

    Preconditions.checkArgument(goldResults.keySet().size() <= 2,
        "Unknown target variable");
    Preconditions.checkArgument(predResults.keySet().size() <= 2,
        "Unknown target variable");

    String goldVar = null;
    String goldVarName = null;
    for (String key : goldResults.keySet()) {
      if (key.equals("targetValue")) {
        goldVarName = key;
      } else if (!key.contains("name")) {
        goldVar = key;
      }
    }

    String predVar = null;
    String predVarName = null;
    for (String key : predResults.keySet()) {
      if (!key.contains("name")) {
        predVar = key;
      } else if (predResults.get(key).size() > 0) {
        predVarName = key;
      }
    }

    // Preconditions.checkArgument(goldVar != null && predVar != null,
    // "No target variable");
    if (goldVarName != null && goldVarName.equals("targetValue")) {
      LinkedHashSet<String> goldAnswers = goldResults.get(goldVarName);

      boolean hasDate =
          (goldAnswers.size() > 0 && goldAnswers.iterator().next()
              .contains("XMLSchema#datetime")) ? true : false;
      if (hasDate) {
        goldAnswers = convertDatesToYears(goldAnswers);
      }

      LinkedHashSet<String> predAnswers =
          predVarName != null ? predResults.get(predVarName) : predResults
              .get(predVar);

      LinkedHashSet<String> predAnswersCleaned = new LinkedHashSet<>();
      for (String predAnswer : predAnswers) {
        predAnswer = predAnswer.split("\\^\\^")[0];
        predAnswer = predAnswer.replaceAll("@[a-zA-Z\\-]+$", "");
        if (hasDate) {
          Matcher matcher = Pattern.compile("([0-9]{3,4})").matcher(predAnswer);
          if (matcher.find()) {
            predAnswer = matcher.group(1);
          }
        }
        if (!goldAnswers.contains(predAnswer)) {
          return false;
        }
        predAnswersCleaned.add(predAnswer);
      }

      if (predAnswersCleaned.size() != goldAnswers.size()) {
        return false;
      }
      return predAnswersCleaned.equals(goldAnswers);
    } else if (goldVar.equals("answerSubset") || goldVar.equals("answer")) {
      // If the gold answers are subset of the predicted answers, return true.
      HashSet<String> predAnswersCleaned = new HashSet<>();
      LinkedHashSet<String> predAnswers = predResults.get(predVar);
      for (String predAnswer : predAnswers) {
        boolean answerIsDate = predAnswer.contains("XMLSchema#datetime");
        predAnswer = predAnswer.split("\\^\\^")[0];
        String[] answers = predAnswer.split("/");
        predAnswer = answers[answers.length - 1];
        if (answerIsDate) {
          Matcher matcher = Pattern.compile("([0-9]{3,4})").matcher(predAnswer);
          if (matcher.find()) {
            predAnswer = matcher.group(1);
          }
        }
        predAnswersCleaned.add(predAnswer);
      }
      if (goldVar.equals("answerSubset"))
        return predAnswersCleaned.containsAll(goldResults.get(goldVar));
      else
        return predAnswersCleaned.equals(goldResults.get(goldVar));
    } else {
      return goldResults.get(goldVar).equals(predResults.get(predVar));
    }
  }

  public static LinkedHashSet<String> convertDatesToYears(Set<String> results) {
    if (results == null) {
      return null;
    }
    LinkedHashSet<String> dates = Sets.newLinkedHashSet();
    for (String result : results) {
      // date = 2008-12-31^^http://www.w3.org/2001/XMLSchema#datetime
      if (result.contains("XMLSchema#datetime")) {
        String date = Splitter.on("^^").split(result).iterator().next();
        date = Splitter.on("-").split(date).iterator().next();
        dates.add(date);
      }
    }
    return dates;
  }

  private static class QueryExecutionTaskJdbc implements Callable<Void> {
    private final List<Map<String, String>> results;
    private final VirtGraph virtgraph;
    private final String query;
    private QueryExecution vqe = null;

    public QueryExecutionTaskJdbc(VirtGraph virtGraph, String query,
        List<Map<String, String>> results) {
      this.virtgraph = virtGraph;
      this.results = results;
      this.query = query;
    }

    @Override
    public Void call() throws Exception {
      Query sparql;
      try {
        String newQuery = String.format("%s %s", XSD_PREFIX, query);
        sparql = QueryFactory.create(newQuery);
      } catch (Exception e) {
        // Bad query.
        return null;
      }

      VirtuosoQueryExecution vqe = null;
      try {
        vqe = VirtuosoQueryExecutionFactory.create(sparql, virtgraph);
        ResultSet resultSet = vqe.execSelect();
        while (resultSet != null && resultSet.hasNext()) {
          QuerySolution result = resultSet.next();
          Iterator<String> it = result.varNames();
          Map<String, String> varValue = new LinkedHashMap<>();
          while (it.hasNext()) {
            String var = it.next();
            RDFNode value = result.get(var);
            if (value != null) {
              varValue.put(var, value.toString());
            }
          }

          if (varValue.size() > 0) {
            results.add(varValue);
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      } finally {
        vqe.close();
      }
      return null;
    }

    public void close() {
      if (vqe != null && !vqe.isClosed()) {
        vqe.close();
      }
    }
  }

  private static class QueryExecutionTaskHttp implements Callable<Void> {
    private final List<Map<String, String>> results;
    private final String httpServer;
    private final String query;
    private InputStream responseRecieved = null;

    public QueryExecutionTaskHttp(String httpServer, String query,
        List<Map<String, String>> results) {
      this.httpServer = httpServer;
      this.results = results;
      this.query = query;
    }

    @Override
    public Void call() throws Exception {
      String charset = "UTF-8";

      URIBuilder builder = new URIBuilder(httpServer);
      builder.addParameter("query", query);
      builder.addParameter("format", "application/sparql-results+json");
      builder.addParameter("timeout", "0");

      URL url = builder.build().toURL();
      URLConnection connection = url.openConnection();

      responseRecieved = connection.getInputStream();
      String content = null;

      content = IOUtils.toString(responseRecieved, charset);
      content = content.trim().replace("\n", " ");

      JsonParser parser = new JsonParser();

      JsonArray resultSet = new JsonArray();
      try {
        resultSet =
            parser.parse(content).getAsJsonObject().get("results")
                .getAsJsonObject().get("bindings").getAsJsonArray();
      } catch (Exception e) {
        return null;
      }

      for (JsonElement result : resultSet) {
        JsonObject resultObj = result.getAsJsonObject();
        Map<String, String> varValue = new LinkedHashMap<>();
        for (Entry<String, JsonElement> varEntry : resultObj.entrySet()) {
          String var = varEntry.getKey();
          JsonObject valueObj = varEntry.getValue().getAsJsonObject();
          String value = valueObj.get("value").getAsString();

          if (valueObj.has("datatype")) {
            value += "^^<" + valueObj.get("datatype") + ">";
          }
          varValue.put(var, value);
        }
        if (varValue.size() > 0) {
          results.add(varValue);
        }
      }
      return null;
    }

    public void close() {
      if (responseRecieved != null) {
        try {
          responseRecieved.close();
        } catch (IOException e) {
          // Connection is already closed. Do nothing.
        }
      }
    }
  }

  public static void main(String[] args) {
    String url;
    if (args.length == 0) {
      url = "jdbc:virtuoso://bravas:1111";
    } else {
      url = args[0];
    }

    String httpUrl = "http://bravas:8890/sparql";

    String query =
        "PREFIX fb: <http://rdf.freebase.com/ns/> PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> SELECT DISTINCT ?rel1 ?rel2 from <http://rdf.freebase.com> WHERE { fb:m.017nt ?rel1 ?m . ?m fb:type.object.type ?z . ?z fb:freebase.type_hints.mediator true . ?m ?rel2 fb:m.04sv4 . }";

    // String query =
    // "SELECT * FROM <http://film.freebase.com> WHERE { ?s ?p ?o . } limit 100";

    RdfGraphTools rdfGraphTools =
        new RdfGraphTools(url, httpUrl, "dba", "dba", 10);

    long startTime = System.currentTimeMillis();
    System.out.println(rdfGraphTools.runQueryHttpSolutions(query));

    System.out.println(rdfGraphTools.runQueryJdbc(query));
    System.out.println(rdfGraphTools.runQueryHttp(query));
    System.out.println(rdfGraphTools.runQueryJdbcSolutions(query));
    System.out.println(rdfGraphTools.runQueryJdbcSolutions(query + " "));
    long stopTime = System.currentTimeMillis();
    long elapsedTime = stopTime - startTime;
    System.out.println(elapsedTime);
  }
}
