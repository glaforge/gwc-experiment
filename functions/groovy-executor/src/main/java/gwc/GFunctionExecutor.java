package gwc;

import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.google.cloud.functions.*;
import com.google.gson.Gson;
import groovy.lang.*;
import groovy.util.logging.Log;
import gwc.representations.*;
import gwc.spock.*;
import gwc.util.*;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.messages.*;

@Log
public class GFunctionExecutor implements HttpFunction {
  private static final Logger LOG = Logger.getLogger(GFunctionExecutor.class.getName());

  private static final Pattern SPOCK_SCRIPT = Pattern.compile("extends\\s+(?:spock\\.lang\\.)?Specification");
  private static final List<String> FILTER_STACKTRACE = List.of(
    "com.google.cloud.", "org.eclipse.jetty.",
    "java.", "javax.", "sun.", "jdk.",
    "groovy", "org.codehaus.groovy.", "org.apache.groovy",
    "gwc.");
  private static final Gson GSON = new Gson();


  public GFunctionExecutor() {
    LOG.info("Groovy function executor initialized");
    System.setProperty("grape.root", "/tmp/.groovy/grapes/");
    // Disable Spock's version check for Groovy 5
    if (GroovySystem.getVersion().startsWith("5.")) {
      System.setProperty("spock.iKnowWhatImDoing.disableGroovyVersionCheck", "true");
    }
  }

  @Override
  public void service(HttpRequest request, HttpResponse response) throws Exception {
    response.appendHeader("Access-Control-Allow-Origin", "*");

    if ("OPTIONS".equals(request.getMethod())) {
      handlePreFlightRequest(response);
    } else if ("POST".equals(request.getMethod())) {
      if (!"application/json".equalsIgnoreCase((request.getContentType().orElse("")))) {
        response.setStatusCode(406);
      } else {
        handleRealInvocation(request, response);
      }
    } else {
      response.setStatusCode(204);
    }
  }

  private void handlePreFlightRequest(HttpResponse response) {
    response.appendHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
    response.appendHeader("Access-Control-Allow-Headers", "Content-Type");
    response.appendHeader("Access-Control-Max-Age", "3600");
  }

  private void handleRealInvocation(HttpRequest request, HttpResponse response) throws IOException {
    ScriptRequest scriptRequest = GSON.fromJson(request.getReader(), ScriptRequest.class);
    String inputScriptOrClass = scriptRequest.getCode();
    LOG.info("Input code:\n---\n" + inputScriptOrClass + "\n---\n");

    var errorOutput = new StringBuilder();

    OutputRedirector outputRedirector = new OutputRedirector();
    Object result = null;
    ExecutionInfo stats = new ExecutionInfo();
    try (var ignore = new MetaClassRegistryGuard();
         var ignore2 = outputRedirector.redirect();
         var igonre3 = new SystemPropertiesGuard()) {
      long executionStart = System.currentTimeMillis();
      boolean isSpock = SPOCK_SCRIPT.matcher(inputScriptOrClass).find();
      if ("ast".equalsIgnoreCase(scriptRequest.getAction())) {
        result = transpileScript(inputScriptOrClass, scriptRequest.getAstPhase(), isSpock);
      } else {
        if (isSpock) {
          result = executeSpock(inputScriptOrClass);
        } else {
          result = executeGroovyScript(inputScriptOrClass, outputRedirector);
        }
      }
      stats.setExecutionTime(System.currentTimeMillis() - executionStart);
    } catch (MultipleCompilationErrorsException e) {
      handleCompilationErrors(errorOutput, e);
    } catch (Throwable t) {
      throwableToErrorOutput(errorOutput, t);
    }

    String outOutput = outputRedirector.getOutput();
    LOG.info("Output:\n---\n" + outOutput + "\n---\n");
    LOG.info("Result:\n---\n" + result + "\n---\n");
    LOG.info("Error:\n---\n" + errorOutput + "}\n---\n");

    response.setContentType("application/json");

    ExecutionResult executionResult = new ExecutionResult(
      outOutput,
      errorOutput.toString(),
      result,
      stats);
    try (Writer writer = response.getWriter()) {
      String responseContent;
      try {
        responseContent = GSON.toJson(executionResult);
      } catch (Exception e) { // serialization of result may fail, so catch the exception
        errorOutput.append("\nFailed to serialize result: ").append(e.getMessage());
        executionResult.setErr(errorOutput.toString());
        executionResult.setResult(null);
        responseContent = GSON.toJson(executionResult);
      } catch (StackOverflowError e) { // serialization of result may fail, so catch the exception
        errorOutput.append("\nFailed to serialize result due to circular references.");
        executionResult.setErr(errorOutput.toString());
        executionResult.setResult(null);
        responseContent = GSON.toJson(executionResult);
      }
      writer.write(responseContent);
    }
  }

  private Object executeGroovyScript(String inputScriptOrClass, OutputRedirector outputRedirector) {
    var binding = new Binding();
    binding.setVariable("out", outputRedirector.getOutPrintStream());
    var shell = new GroovyShell(binding);
    return shell.evaluate(inputScriptOrClass);
  }

  private Object executeSpock(String inputScriptOrClass) {
    ScriptRunner scriptRunner = new ScriptRunner();
    // TODO revisit colored output
    scriptRunner.setDisableColors(true);
    return scriptRunner.run(inputScriptOrClass);
  }

  private String transpileScript(String script, String astPhase, boolean isSpock) {
    return new AstRenderer().render(script, astPhase, isSpock);
  }

  private void handleCompilationErrors(StringBuilder errorOutput, MultipleCompilationErrorsException e) {
    e.getErrorCollector().getErrors().forEach(err -> {
      if (err instanceof SimpleMessage) {
        errorOutput.append(((SimpleMessage)err).getMessage());
      } else if (err instanceof ExceptionMessage) {
        errorOutput.append(((ExceptionMessage)err).getCause().getMessage());
      } else if (err instanceof SyntaxErrorMessage) {
        errorOutput.append(((SyntaxErrorMessage)err).getCause().getMessage());
      }
    });
  }

  private void throwableToErrorOutput(StringBuilder errorOutput, Throwable t) {
    sanitizeStacktrace(t);
    var errorWriter = new StringWriter();
    t.printStackTrace(new PrintWriter(errorWriter));
    errorOutput.append(errorWriter);
  }

  private void sanitizeStacktrace(Throwable t) {
    var clean = Arrays.stream(t.getStackTrace())
      .filter(stackTraceElement -> FILTER_STACKTRACE.stream()
        .noneMatch(it -> stackTraceElement.getClassName().startsWith(it)))
      .toArray(StackTraceElement[]::new);
    t.setStackTrace(clean);
  }
}
