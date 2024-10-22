package gov.cms.madie.madiefhirservice.factories;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.validation.FhirValidator;
import gov.cms.madie.madiefhirservice.exceptions.UnsupportedTypeException;
import gov.cms.madie.models.common.ModelType;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ModelAwareFhirFactory {

  private final Map<String, FhirValidator> fhirValidatorMap;
  private final Map<String, FhirContext> fhirContextMap;

  @Autowired
  public ModelAwareFhirFactory(
      Map<String, FhirValidator> fhirValidatorMap, Map<String, FhirContext> fhirContextMap) {
    this.fhirValidatorMap = fhirValidatorMap;
    this.fhirContextMap = fhirContextMap;
  }

  public FhirValidator getValidatorForModel(ModelType modelType) {
    FhirValidator validator = fhirValidatorMap.get(modelType.getShortValue() + "NpmFhirValidator");
    if (validator == null) {
      throw new UnsupportedTypeException(this.getClass().getName(), modelType.toString());
    }

    return validator;
  }

  public FhirContext getContextForModel(ModelType modelType) {
    FhirContext context = fhirContextMap.get(modelType.getShortValue() + "FhirContext");
    if (context == null) {
      throw new UnsupportedTypeException(this.getClass().getName(), modelType.toString());
    }
    return context;
  }

  public IParser getParserForModel(ModelType modelType) {
    FhirContext context = getContextForModel(modelType);

    if (context == null) {
      throw new UnsupportedTypeException(this.getClass().getName(), modelType.toString());
    }

    return context
        .newJsonParser()
        .setParserErrorHandler(new StrictErrorHandler())
        .setPrettyPrint(true);
  }

  /**
   * Convenience method to encapsulate model-specific parsing as the parseResource call requires a
   * concrete class. QDM ModelType is not supported.
   *
   * @param modelType
   * @param bundleString
   * @return
   * @throws DataFormatException
   * @throws ClassCastException
   */
  public IBaseBundle parseForModel(ModelType modelType, String bundleString) {
    IParser parser = this.getParserForModel(modelType);

    IBaseBundle bundle;
    switch (modelType) {
      case QDM_5_6 -> throw new UnsupportedTypeException(this.getClass().getName(), "QDM v5.6");
      case QI_CORE, QI_CORE_6_0_0 ->
          bundle = parser.parseResource(org.hl7.fhir.r4.model.Bundle.class, bundleString);
      default -> throw new UnsupportedTypeException(this.getClass().getName(), "N/A");
    }
    return bundle;
  }
}
