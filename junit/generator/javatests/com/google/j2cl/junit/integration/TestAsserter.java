/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.junit.integration;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.j2cl.junit.integration.IntegrationTestBase.TestMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TestAsserter {

  private final List<String> consoleLogs;
  private final TestMode testMode;
  private TestResult testResult;

  TestAsserter(TestMode testMode, List<String> consoleLogs) {
    this.testMode = testMode;
    this.consoleLogs = consoleLogs;
  }

  public void matches(TestResult testResult) {
    this.testResult = testResult;
    try {
      assertTestSummary();
      assertTestResults();
      assertLogLines();
    } catch (AssertionError e) {
      StringBuilder builder = new StringBuilder();
      builder.append("\n#######################################\n");
      builder.append("Test output start:\n");
      builder.append("#######################################\n");
      builder.append(consoleLogs.stream().collect(Collectors.joining("\n")));
      builder.append("\n#######################################\n");
      builder.append("Test output end\n");
      builder.append("#######################################\n");
      throw new AssertionError(builder.toString(), e);
    }
  }

  public void matches(List<String> stacktrace) {
    new StacktraceAsserter(testMode, consoleLogs).matches(stacktrace);
  }

  private void assertTestSummary() {
    int fails = testResult.fails().size();
    int errors = testResult.errors().size();
    int succeeds = testResult.succeeds().size();
    int testCount = fails + errors + succeeds;
    if (testMode.isWeb()) {
      // Like Junit4, J2CL always counts errors as failures
      fails += errors;
      errors = 0;
      // TODO(b/32608089): jsunit_test does not report number of tests correctly
      testCount = 1;
      // Since total number of tests cannot be asserted; ensure nummber of succeeds is correct.
      assertThat(consoleLogs.stream().filter(x -> x.contains(": PASSED"))).hasSize(succeeds);
    }

    if (fails + errors > 0) {
      assertTestSummaryForFailure(fails, errors, testCount);
    } else {
      assertTestSummaryForSuccess(testCount);
    }
  }

  private void assertTestResults() {
    testResult.succeeds().forEach(this::assertTestMethodSucceeded);
    testResult.fails().entries().forEach(this::assertTestMethodFailed);
    testResult.errors().entrySet().forEach(this::assertTestMethodFailed);
  }

  private void assertLogLines() {
    for (String sequence : testResult.logLinesSequences()) {
      assertLogsContains(sequence);
    }

    List<String> javaLogLines = extractJavaMessages();
    for (ImmutableList<String> javaLogLineSequence : testResult.javaLogLinesSequences()) {
      assertThat(javaLogLines).containsAtLeastElementsIn(javaLogLineSequence).inOrder();
    }
    Iterable<String> allExpectedJavaLog = Iterables.concat(testResult.javaLogLinesSequences());
    assertThat(javaLogLines).containsExactlyElementsIn(allExpectedJavaLog);

    for (String blackListedString : testResult.blackList()) {
      assertLogsNotContains(blackListedString);
    }
  }

  private List<String> extractJavaMessages() {
    final String msgMarker = " [java_message_from_test] ";
    return consoleLogs
        .stream()
        .filter(input -> input.contains(msgMarker))
        .map(input -> input.substring(input.indexOf(msgMarker) + msgMarker.length()))
        .collect(toImmutableList());
  }

  private void assertLogsContains(String format, Object... args) {
    String shouldContain = String.format(format, args);
    assertTrue("Logs should contain: " + shouldContain, oneLineContains(shouldContain));
  }

  private void assertLogsNotContains(String shouldNotContain) {
    assertFalse("Logs should contain: " + shouldNotContain, oneLineContains(shouldNotContain));
  }

  private void assertTestSummaryForFailure(int failures, int errors, int total) {
    if (errors == 0) {
      assertLogsContains("Tests run: %d,  Failures: %d", total, failures);
    } else {
      assertLogsContains("Tests run: %d,  Failures: %d,  Errors: %d", total, failures, errors);
    }
    assertLogsContains("FAILURES!!!");
  }

  private void assertTestSummaryForSuccess(int count) {
    if (count == 1) {
      assertLogsContains("OK (1 test)");
    } else {
      assertLogsContains("OK (%d tests)", count);
    }

    assertLogsNotContains("FAILURES!!!");
  }

  private void assertTestMethodSucceeded(String method) {
    method = getTestMethodName(method);

    if (testMode.isWeb()) {
      assertLogsContains("%s : PASSED", method);
    } else {
      assertLogsNotContains(getJunitTestFailureMsg(method));
    }
  }

  private void assertTestMethodFailed(Map.Entry<String, String> testEntry) {
    String method = getTestMethodName(testEntry.getKey());
    if (testMode.isWeb()) {
      assertLogsContains("%s : FAILED", method);
    } else {
      assertLogsContains(getJunitTestFailureMsg(method));
    }

    // TODO(b/149936597): Improve wasm exception and then change this testError to be more specific
    if (testMode.isJ2wasm()) {
      assertLogsContains("wasm exception");
    } else {
      assertLogsContains(testEntry.getValue());
    }
  }

  private String getJunitTestFailureMsg(String method) {
    // This is approximate but good enough for our testing purposes
    return String.format(
        ") %s(%s.%s)", method, testResult.packageName(), testResult.testClassName());
  }

  private String getTestMethodName(String methodName) {
    return testMode.isWeb() && !methodName.startsWith("test") ? "test_" + methodName : methodName;
  }

  private boolean oneLineContains(String shouldContain) {
    return consoleLogs.stream().anyMatch(line -> line.contains(shouldContain));
  }
}
