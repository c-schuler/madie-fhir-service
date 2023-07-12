package gov.cms.madie.madiefhirservice.utils;

import gov.cms.madie.models.measure.Measure;
import gov.cms.madie.models.measure.TestCase;

public class ExportFileNamesUtil {

  public static String getExportFileName(Measure measure) {
    if (measure.getModel().startsWith("QI-Core")) {
      return measure.getEcqmTitle() + "-v" + measure.getVersion() + "-FHIR";
    }
    return measure.getEcqmTitle() + "-v" + measure.getVersion() + "-" + measure.getModel();
  }

  public static String getTestCaseExportFileName(Measure measure, TestCase testCase) {
    return measure.getEcqmTitle()
        + "-v"
        + measure.getVersion().toString()
        + "-"
        + testCase.getSeries()
        + "-"
        + testCase.getTitle();
  }

  public static String getTestCaseExportZipName(Measure measure) {
    return measure.getEcqmTitle()
        + "-v"
        + measure.getVersion().toString()
        + "-"
        + measure.getModel()
        + "-TestCases";
  }
}
