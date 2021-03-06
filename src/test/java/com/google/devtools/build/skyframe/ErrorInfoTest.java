// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.skyframe;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.skyframe.SkyFunctionException.ReifiedSkyFunctionException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;

/** Tests for the non-trivial creation logic of {@link ErrorInfo}. */
@RunWith(JUnit4.class)
public class ErrorInfoTest {

  /** Dummy SkyFunctionException implementation for the sake of testing. */
  private static class DummySkyFunctionException extends SkyFunctionException {
    private final boolean isCatastrophic;

    public DummySkyFunctionException(Exception cause, boolean isTransient,
        boolean isCatastrophic) {
      super(cause, isTransient ? Transience.TRANSIENT : Transience.PERSISTENT);
      this.isCatastrophic = isCatastrophic;
    }

    @Override
    public boolean isCatastrophic() {
      return isCatastrophic;
    }
  }

  @Test
  public void testFromException() {
    Exception exception = new IOException("ehhhhh");
    SkyKey causeOfException = new SkyKey(SkyFunctionName.create("CAUSE"), 1234);
    DummySkyFunctionException dummyException =
        new DummySkyFunctionException(exception, /*isTransient=*/ true, /*isCatastrophic=*/ false);

    ErrorInfo errorInfo = ErrorInfo.fromException(
        new ReifiedSkyFunctionException(dummyException, causeOfException));

    assertThat(errorInfo.getRootCauses()).containsExactly(causeOfException);
    assertThat(errorInfo.getException()).isSameAs(exception);
    assertThat(errorInfo.getRootCauseOfException()).isSameAs(causeOfException);
    assertThat(errorInfo.getCycleInfo()).isEmpty();
    assertThat(errorInfo.isTransient()).isTrue();
    assertThat(errorInfo.isCatastrophic()).isFalse();
  }

  @Test
  public void testFromCycle() {
    CycleInfo cycle = new CycleInfo(
        ImmutableList.of(new SkyKey(SkyFunctionName.create("PATH"), 1234)),
        ImmutableList.of(new SkyKey(SkyFunctionName.create("CYCLE"), 4321)));

    ErrorInfo errorInfo = ErrorInfo.fromCycle(cycle);

    assertThat(errorInfo.getRootCauses()).isEmpty();
    assertThat(errorInfo.getException()).isNull();
    assertThat(errorInfo.getRootCauseOfException()).isNull();
    assertThat(errorInfo.isTransient()).isFalse();
    assertThat(errorInfo.isCatastrophic()).isFalse();
  }

  @Test
  public void testFromChildErrors() {
    CycleInfo cycle = new CycleInfo(
        ImmutableList.of(new SkyKey(SkyFunctionName.create("PATH"), 1234)),
        ImmutableList.of(new SkyKey(SkyFunctionName.create("CYCLE"), 4321)));
    ErrorInfo cycleErrorInfo = ErrorInfo.fromCycle(cycle);

    Exception exception1 = new IOException("ehhhhh");
    SkyKey causeOfException1 = new SkyKey(SkyFunctionName.create("CAUSE1"), 1234);
    DummySkyFunctionException dummyException1 =
        new DummySkyFunctionException(exception1, /*isTransient=*/ true, /*isCatastrophic=*/ false);
    ErrorInfo exceptionErrorInfo1 = ErrorInfo.fromException(
        new ReifiedSkyFunctionException(dummyException1, causeOfException1));

    // N.B this ErrorInfo will be catastrophic.
    Exception exception2 = new IOException("blahhhhh");
    SkyKey causeOfException2 = new SkyKey(SkyFunctionName.create("CAUSE2"), 5678);
    DummySkyFunctionException dummyException2 =
        new DummySkyFunctionException(exception2, /*isTransient=*/ true, /*isCatastrophic=*/ true);
    ErrorInfo exceptionErrorInfo2 = ErrorInfo.fromException(
        new ReifiedSkyFunctionException(dummyException2, causeOfException2));

    SkyKey currentKey = new SkyKey(SkyFunctionName.create("CURRENT"), 9876);

    ErrorInfo errorInfo = ErrorInfo.fromChildErrors(
        currentKey, ImmutableList.of(cycleErrorInfo, exceptionErrorInfo1, exceptionErrorInfo2));

    assertThat(errorInfo.getRootCauses()).containsExactly(causeOfException1, causeOfException2);

    // For simplicity we test the current implementation detail that we choose the first non-null
    // (exception, cause) pair that we encounter. This isn't necessarily a requirement of the
    // interface, but it makes the test convenient and is a way to document the current behavior.
    assertThat(errorInfo.getException()).isSameAs(exception1);
    assertThat(errorInfo.getRootCauseOfException()).isSameAs(causeOfException1);

    assertThat(errorInfo.getCycleInfo()).containsExactly(
        new CycleInfo(
            ImmutableList.of(currentKey, Iterables.getOnlyElement(cycle.getPathToCycle())),
            cycle.getCycle()));
    assertThat(errorInfo.isTransient()).isFalse();
    assertThat(errorInfo.isCatastrophic()).isTrue();
  }

  @Test
  public void testCannotCreateErrorInfoWithoutExceptionOrCycle() {
    try {
      new ErrorInfo(
          NestedSetBuilder.<SkyKey>emptySet(Order.COMPILE_ORDER),
          /*exception=*/ null,
          /*rootCauseOfException=*/ null,
          ImmutableList.<CycleInfo>of(),
          false,
          false);
    } catch (IllegalStateException e) {
      // Brittle, but confirms we failed for the right reason.
      assertThat(e)
          .hasMessage("At least one of exception and cycles must be non-null/empty, respectively");
    }
  }

  @Test
  public void testCannotCreateErrorInfoWithExceptionButNoRootCause() {
    try {
      new ErrorInfo(
          NestedSetBuilder.<SkyKey>emptySet(Order.COMPILE_ORDER),
          new IOException("foo"),
          /*rootCauseOfException=*/ null,
          ImmutableList.<CycleInfo>of(),
          false,
          false);
    } catch (IllegalStateException e) {
      // Brittle, but confirms we failed for the right reason.
      assertThat(e.getMessage())
          .startsWith("exception and rootCauseOfException must both be null or non-null");
    }
  }
}

