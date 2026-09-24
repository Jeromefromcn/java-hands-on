package com.jerome.slledger.bootstrap.demo.tx.selfinvocation;

/** Fix 1: the transactional method lives on a second bean, so the call crosses a proxy. */
public class SplitReturnService {

  private final ReturnRecorder recorder;

  public SplitReturnService(ReturnRecorder recorder) {
    this.recorder = recorder;
  }

  public void returnAll() {
    recorder.recordReturn();
  }
}
