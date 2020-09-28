/*
 * Copyright 2006-2020 The MZmine Development Team
 *
 * This file is part of MZmine.
 *
 * MZmine is free software; you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * MZmine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with MZmine; if not,
 * write to the Free Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301
 * USA
 */

package io.github.mzmine.parameters.parametertypes.combonested;

import io.github.mzmine.parameters.Parameter;
import io.github.mzmine.parameters.ParameterSet;
import io.github.mzmine.parameters.UserParameter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Nested combo parameter
 * presents a list of choices each with their own set of subparameters
 * displayed with combobox and a list of subparameters below based on the combobox choice
 * allows for a flat definition of all necessary module parameters in a single module parameters class
 * but the nested structure is given only to the nested combo parameter with a map storing a separate
 * parameters set for each of the combo choices
 * for example usage see {@code MassCalibrationParameters} and io.github.mzmine.modules.dataprocessing.masscalibration
 */
public class NestedComboParameter implements UserParameter<NestedCombo, NestedComboboxComponent> {

  private final String name;
  private final String description;
  protected TreeMap<String, ParameterSet> choices;
  protected String defaultChoice;
  protected boolean showNestedParamsOnStart;
  protected Integer prefWidth;
  private NestedCombo value;

  public NestedComboParameter(String name, String description,
                              TreeMap<String, ParameterSet> choices, String defaultChoice,
                              boolean showNestedParamsOnStart, Integer prefWidth) {
    this.name = name;
    this.description = description;
    this.choices = choices;
    this.defaultChoice = defaultChoice;
    this.showNestedParamsOnStart = showNestedParamsOnStart;
    this.prefWidth = prefWidth;
  }

  public NestedComboParameter(String name, String description,
                              TreeMap<String, ParameterSet> choices, String defaultChoice) {
    this(name, description, choices, defaultChoice, true, null);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public NestedComboboxComponent createEditingComponent() {
    return new NestedComboboxComponent(choices, value != null ? value.getCurrentChoice() : defaultChoice,
            showNestedParamsOnStart, prefWidth);
  }

  @Override
  public NestedComboParameter cloneParameter() {
    TreeMap<String, ParameterSet> clonedChoices = new TreeMap<>();
    for (String choice : choices.keySet()) {
      clonedChoices.put(choice, choices.get(choice).cloneParameterSet());
    }
    NestedComboParameter copy = new NestedComboParameter(name, description, clonedChoices, defaultChoice,
            showNestedParamsOnStart, prefWidth);
    copy.setValue(this.getValue());
    return copy;
  }

  @Override
  public void setValueFromComponent(NestedComboboxComponent component) {
    component.updateParameterSetFromComponents();
    this.value = new NestedCombo(component.choices, component.getCurrentChoice());
  }

  @Override
  public void setValueToComponent(NestedComboboxComponent component, NestedCombo newValue) {
    component.setCurrentChoice(newValue.currentChoice);
    for (String choice : component.choices.keySet()) {
      for (Parameter p : choices.get(choice).getParameters())
        component.choices.get(choice).getParameter(p).setValue(newValue.choices.get(choice).getParameter(p).getValue());
    }
  }

  @Override
  public NestedCombo getValue() {
    return value;
  }

  @Override
  public void setValue(NestedCombo newValue) {
    this.value = newValue;
  }

  @Override
  public void loadValueFromXML(Element xmlElement) {
    NodeList currentChoiceList = xmlElement.getElementsByTagName("currentchoice");
    if (currentChoiceList.getLength() == 0) {
      return;
    }
    String currentChoice = currentChoiceList.item(currentChoiceList.getLength() - 1).getTextContent();

//    TreeMap<String, ParameterSet> choices = new TreeMap<>();
    NodeList choicesList = xmlElement.getElementsByTagName("choice");
    for (int i = 0; i < choicesList.getLength(); i++) {
      Element choice = (Element) choicesList.item(i);
      String choiceName = choice.getAttribute("name");
      ParameterSet choiceParameterSet = choices.get(choiceName).cloneParameterSet();
      choiceParameterSet.loadValuesFromXML((Element) choice.getElementsByTagName("value").item(0));
      choices.put(choiceName, choiceParameterSet);
    }

    this.value = new NestedCombo(choices, currentChoice);
  }

  @Override
  public void saveValueToXML(Element xmlElement) {
    if (value == null) {
      return;
    }
    Document parentDocument = xmlElement.getOwnerDocument();
    Element currentChoiceElement = parentDocument.createElement("currentchoice");
    currentChoiceElement.setTextContent(value.getCurrentChoice());
    xmlElement.appendChild(currentChoiceElement);

    for (Map.Entry<String, ParameterSet> choice : value.getChoices().entrySet()) {
      Element choiceElement = parentDocument.createElement("choice");
      choiceElement.setAttribute("name", choice.getKey());
      Element choiceInnerElement = parentDocument.createElement("value");
      choice.getValue().saveValuesToXML(choiceInnerElement);
      choiceElement.appendChild(choiceInnerElement);
      xmlElement.appendChild(choiceElement);
    }
  }

  @Override
  public boolean checkValue(Collection<String> errorMessages) {
    if (value == null) {
      errorMessages.add(name + " is not set properly");
      return false;
    }

    return value.getCurrentChoiceParameterSet().checkParameterValues(errorMessages);
  }

}
