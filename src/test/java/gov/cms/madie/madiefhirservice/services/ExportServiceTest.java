package gov.cms.madie.madiefhirservice.services;

import static gov.cms.madie.madiefhirservice.utils.MeasureTestHelper.createFhirResourceFromJson;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

import ca.uhn.fhir.context.FhirContext;
import gov.cms.madie.madiefhirservice.config.ElmTranslatorClientConfig;
import gov.cms.madie.madiefhirservice.utils.ResourceFileUtil;
import gov.cms.madie.models.common.Version;
import gov.cms.madie.models.measure.Measure;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest implements ResourceFileUtil {

  @Mock private FhirContext fhirContext;
  @Mock private RestTemplate elmTranslatorRestTemplate;
  @Mock private ElmTranslatorClientConfig elmTranslatorClientConfig;

  @Spy @InjectMocks private ExportService exportService;

  private Measure measure;
  private Bundle measureBundle;
  private String humanReadable;

  @BeforeEach
  public void setUp() {
    humanReadable = getStringFromTestResource("/humanReadable/humanReadable_test");

    lenient().when(elmTranslatorClientConfig.getCqlElmServiceBaseUrl()).thenReturn("http://test");
    lenient().when(elmTranslatorClientConfig.getHumanReadableUri()).thenReturn("/human-readable");

    measure =
        Measure.builder()
            .active(true)
            .ecqmTitle("ExportTest")
            .id("xyz-p13r-13ert")
            .cql("test cql")
            .cqlErrors(false)
            .measureSetId("IDIDID")
            .measureName("MSR01")
            .version(new Version(1, 0, 0))
            .createdAt(Instant.now())
            .createdBy("test user")
            .lastModifiedAt(Instant.now())
            .lastModifiedBy("test user")
            .model("QI-Core v4.1.1")
            .build();
    measureBundle =
        createFhirResourceFromJson(
            getStringFromTestResource("/bundles/export_test.json"), Bundle.class);
  }

  @Test
  void testCreateExportsForMeasure() throws IOException {
    when(fhirContext.newJsonParser()).thenReturn(FhirContext.forR4().newJsonParser());
    when(fhirContext.newXmlParser()).thenReturn(FhirContext.forR4().newXmlParser());
    when(elmTranslatorRestTemplate.exchange(
            any(URI.class), eq(HttpMethod.PUT), any(HttpEntity.class), any(Class.class)))
        .thenReturn(ResponseEntity.ok(humanReadable));

    ByteArrayOutputStream out = new ByteArrayOutputStream();

    exportService.createExport(measure, measureBundle, out, "Bearer TOKEN");

    // expected files in export zip
    List<String> expectedFilesInZip =
        List.of(
            "ExportTest-v1.0.000-QI-Core v4.1.1.json",
            "/cql/ExportTest.cql",
            "/cql/FHIRHelpers.cql",
            "/resources/library-ExportTest.json",
            "/resources/library-ExportTest.xml",
            "/resources/library-FHIRHelpers.json",
            "/resources/library-FHIRHelpers.xml",
            "ExportTest-1.0.000-FHIR.html");

    ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
    List<String> actualFilesInZip = getFilesInZip(zipInputStream);
    assertThat(expectedFilesInZip.size(), is(equalTo(actualFilesInZip.size())));
    assertThat(expectedFilesInZip, is(equalTo(actualFilesInZip)));
  }

  @Test
  void testGenerateExportsWhenWritingFileToZipFailed() throws IOException {
    doThrow(new IOException()).when(exportService).addBytesToZip(anyString(), any(), any());
    when(fhirContext.newJsonParser()).thenReturn(FhirContext.forR4().newJsonParser());
    when(elmTranslatorRestTemplate.exchange(
            any(URI.class), eq(HttpMethod.PUT), any(HttpEntity.class), any(Class.class)))
        .thenReturn(ResponseEntity.ok("humanreadable"));

    Exception ex =
        assertThrows(
            RuntimeException.class,
            () ->
                exportService.createExport(
                    measure, measureBundle, OutputStream.nullOutputStream(), "Bearer TOKEN"));
    assertThat(
        ex.getMessage(),
        is(equalTo("Unexpected error while generating exports for measureID: xyz-p13r-13ert")));
  }

  private List<String> getFilesInZip(ZipInputStream zipInputStream) throws IOException {
    ZipEntry entry;
    List<String> actualFilesInZip = new ArrayList<>();
    while ((entry = zipInputStream.getNextEntry()) != null) {
      actualFilesInZip.add(entry.getName());
    }
    return actualFilesInZip;
  }
}
