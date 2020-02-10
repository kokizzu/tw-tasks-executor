package com.transferwise.tasks.test;

public abstract class BaseTestBuilder<B, O> {

  @SuppressWarnings("unchecked")
  protected B me() {
    return (B) this;
  }

  public abstract O build();
}
