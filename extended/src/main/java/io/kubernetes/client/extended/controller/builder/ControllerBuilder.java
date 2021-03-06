package io.kubernetes.client.extended.controller.builder;

import io.kubernetes.client.informer.SharedInformerFactory;

/** The type Controller builder is the entry class of controller builders. */
public class ControllerBuilder {

  /**
   * Default builder is for building default controller.
   *
   * @param factory the informer factory, note that there supposed to be one informer factory
   *     globally in your application.
   * @return the default controller builder
   */
  public static DefaultControllerBuilder defaultBuilder(SharedInformerFactory factory) {
    return new DefaultControllerBuilder(factory);
  }

  /**
   * Controller manager builder is for building controller-manager .
   *
   * @return the controller mananger builder
   */
  public static ControllerManangerBuilder controllerManagerBuilder() {
    return new ControllerManangerBuilder();
  }
}
