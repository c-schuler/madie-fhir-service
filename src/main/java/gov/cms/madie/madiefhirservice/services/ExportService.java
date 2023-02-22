package gov.cms.madie.madiefhirservice.services;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import gov.cms.madie.madiefhirservice.config.ElmTranslatorClientConfig;
import gov.cms.madie.madiefhirservice.exceptions.CqlElmTranslationServiceException;
import gov.cms.madie.madiefhirservice.utils.ExportFileNamesUtil;
import gov.cms.madie.models.measure.Measure;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@AllArgsConstructor
public class ExportService {

  private FhirContext fhirContext;
  private RestTemplate elmTranslatorRestTemplate;
  private ElmTranslatorClientConfig elmTranslatorClientConfig;

  private static final String TEXT_CQL = "text/cql";
  private static final String CQL_DIRECTORY = "/cql/";
  private static final String RESOURCES_DIRECTORY = "/resources/";

  public void createExport(
      Measure measure, Bundle bundle, OutputStream outputStream, String accessToken) {
    String exportFileName = ExportFileNamesUtil.getExportFileName(measure);
    String humanReadableFile = getHumanReadable(measure, accessToken);
    log.info("Generating exports for " + exportFileName);
    try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
      addBytesToZip(
          exportFileName + ".json",
          convertFhirResourceToString(bundle, fhirContext.newJsonParser()).getBytes(),
          zos);
      addBytesToZip(
          exportFileName + ".xml",
          convertFhirResourceToString(bundle, fhirContext.newXmlParser()).getBytes(),
          zos);
      addLibraryCqlFilesToExport(zos, bundle);
      addLibraryResourcesToExport(zos, bundle);
      addHumanReadableFile(zos, measure, humanReadableFile);
    } catch (Exception ex) {
      log.error(ex.getMessage());
      throw new RuntimeException(
          "Unexpected error while generating exports for measureID: " + measure.getId());
    }
  }

  private String getHumanReadable(Measure measure, String accessToken) {
    try {
      URI uri =
          URI.create(
              elmTranslatorClientConfig.getCqlElmServiceBaseUrl()
                  + elmTranslatorClientConfig.getHumanReadableUri());
      HttpHeaders headers = new HttpHeaders();
      headers.set(HttpHeaders.AUTHORIZATION, accessToken);
      HttpEntity<Measure> measureEntity = new HttpEntity<>(measure, headers);
      return elmTranslatorRestTemplate
          .exchange(uri, HttpMethod.PUT, measureEntity, String.class)
          .getBody();
    } catch (Exception ex) {
      log.error(
          "An error occurred parsing the human readable response "
              + "from the CQL to ELM translation service",
          ex);
      throw new CqlElmTranslationServiceException(
          "There was an error calling CQL-ELM translation service", ex);
    }
  }

  private void addHumanReadableFile(ZipOutputStream zos, Measure measure, String humanReadableFile)
      throws IOException {
    String humanReadableFileName =
        measure.getEcqmTitle() + "-" + measure.getVersion() + "-FHIR.html";
    addBytesToZip(humanReadableFileName, humanReadableFile.getBytes(), zos);
  }

  private void addLibraryCqlFilesToExport(ZipOutputStream zos, Bundle measureBundle)
      throws IOException {
    Map<String, String> cqlMap = getCQLForLibraries(measureBundle);
    for (Map.Entry<String, String> entry : cqlMap.entrySet()) {
      String filePath = CQL_DIRECTORY + entry.getKey() + ".cql";
      String data = entry.getValue();
      addBytesToZip(filePath, data.getBytes(), zos);
    }
  }

  private void addLibraryResourcesToExport(ZipOutputStream zos, Bundle measureBundle)
      throws IOException {
    List<Library> libraries = getLibraryResources(measureBundle);
    for (Library library : libraries) {
      String json = convertFhirResourceToString(library, fhirContext.newJsonParser());
      String xml = convertFhirResourceToString(library, fhirContext.newXmlParser());
      String fileName = RESOURCES_DIRECTORY + "library-" + library.getName();
      addBytesToZip(fileName + ".json", json.getBytes(), zos);
      addBytesToZip(fileName + ".xml", xml.getBytes(), zos);
    }
  }

  private List<Library> getLibraryResources(Bundle measureBundle) {
    return measureBundle.getEntry().stream()
        .filter(
            entry -> StringUtils.equals("Library", entry.getResource().getResourceType().name()))
        .map(entry -> (Library) entry.getResource())
        .toList();
  }

  private Map<String, String> getCQLForLibraries(Bundle measureBundle) {
    Map<String, String> libraryCqlMap = new HashMap<>();
    List<Library> libraries = getLibraryResources(measureBundle);
    for (Library library : libraries) {
      Attachment attachment = getCqlAttachment(library);
      String cql = new String(attachment.getData());
      libraryCqlMap.put(library.getName(), cql);
    }
    return libraryCqlMap;
  }

  private Attachment getCqlAttachment(Library library) {
    return library.getContent().stream()
        .filter(content -> StringUtils.equals(TEXT_CQL, content.getContentType()))
        .findAny()
        .orElse(null);
  }

  /**
   * Adds the bytes to zip.
   *
   * @param path file name along with path and extension
   * @param input the input byte array
   * @param zipOutputStream the zip
   * @throws IOException the exception
   */
  void addBytesToZip(String path, byte[] input, ZipOutputStream zipOutputStream)
      throws IOException {
    ZipEntry entry = new ZipEntry(path);
    entry.setSize(input.length);
    zipOutputStream.putNextEntry(entry);
    zipOutputStream.write(input);
    zipOutputStream.closeEntry();
  }

  private String convertFhirResourceToString(Resource resource, IParser parser) {
    return parser.setPrettyPrint(true).encodeResourceToString(resource);
  }
}