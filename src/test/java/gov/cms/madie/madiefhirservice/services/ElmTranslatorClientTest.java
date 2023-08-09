package gov.cms.madie.madiefhirservice.services;

import ca.uhn.fhir.context.FhirContext;
import gov.cms.madie.madiefhirservice.config.ElmTranslatorClientConfig;
import gov.cms.madie.madiefhirservice.exceptions.CqlElmTranslationServiceException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r5.model.Library;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ElmTranslatorClientTest {

  @Mock private ElmTranslatorClientConfig elmTranslatorClientConfig;
  @Mock private RestTemplate restTemplate;
  @Mock FhirContext fhirContext;

  @InjectMocks private ElmTranslatorClient elmTranslatorClient;

  private Bundle bundle;

  @BeforeEach
  void beforeEach() {
    lenient().when(elmTranslatorClientConfig.getCqlElmServiceBaseUrl()).thenReturn("http://test");
    lenient()
        .when(elmTranslatorClientConfig.getEffectiveDataRequirementsDataUri())
        .thenReturn("/geteffectivedatarequirements");
    bundle = new Bundle().setType(Bundle.BundleType.TRANSACTION);
  }

  @Test
  public void testGetEffectiveDataRequirementsThrowsException() {
    assertThrows(
        CqlElmTranslationServiceException.class,
        () ->
            elmTranslatorClient.getEffectiveDataRequirements(
                bundle, "TEST_LIBRARYNAME", "TEST_TOKEN", "TEST_MEASURE_ID"));
  }

  @Test
  public void testGetEffectiveDataRequirementsSuccess() {
    String effectiveDR =
        "{\n"
            + "  \"resourceType\": \"Library\",\n"
            + "  \"id\": \"effective-data-requirements\",\n"
            + "  \"status\": \"active\",\n"
            + "  \"type\": {\n"
            + "    \"coding\": [ {\n"
            + "      \"system\": \"http://terminology.hl7.org/CodeSystem/library-type\",\n"
            + "      \"code\": \"module-definition\"\n"
            + "    } ]\n"
            + "  }\n"
            + "}";
    when(restTemplate.exchange(
            any(URI.class), eq(HttpMethod.PUT), any(HttpEntity.class), any(Class.class)))
        .thenReturn(ResponseEntity.ok(effectiveDR));

    when(fhirContext.newJsonParser())
        .thenReturn(FhirContext.forR4().newJsonParser())
        .thenReturn(FhirContext.forR5().newJsonParser());

    Library output =
        elmTranslatorClient.getEffectiveDataRequirements(
            bundle, "TEST_LIBRARY", "TEST_MEASURE_ID", "TEST_TOKEN");
    assertThat(output.getId(), is(equalTo("effective-data-requirements")));
  }
}
