package gov.cms.madie.madiefhirservice.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseDatatype;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactDetail;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Expression;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Measure.MeasureGroupComponent;
import org.hl7.fhir.r4.model.Measure.MeasureGroupPopulationComponent;
import org.hl7.fhir.r4.model.Measure.MeasureGroupStratifierComponent;
import org.hl7.fhir.r4.model.Measure.MeasureSupplementalDataComponent;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import gov.cms.madie.madiefhirservice.constants.UriConstants;
import gov.cms.madie.models.measure.Group;
import gov.cms.madie.models.measure.Measure;
import gov.cms.madie.models.measure.Population;
import gov.cms.madie.models.measure.PopulationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeasureTranslatorService {
  public static final String UNKNOWN = "UNKNOWN";

  @Value("${fhir-base-url}")
  private String fhirBaseUrl;

  public org.hl7.fhir.r4.model.Measure createFhirMeasureForMadieMeasure(Measure madieMeasure) {
    String steward = madieMeasure.getMeasureMetaData().getSteward();
    String copyright = madieMeasure.getMeasureMetaData().getCopyright();
    String disclaimer = madieMeasure.getMeasureMetaData().getDisclaimer();
    String rationale = madieMeasure.getMeasureMetaData().getRationale();

    org.hl7.fhir.r4.model.Measure measure = new org.hl7.fhir.r4.model.Measure();
    measure
        .setName(madieMeasure.getCqlLibraryName())
        .setTitle(madieMeasure.getMeasureName())
        .setExperimental(true)
        .setUrl(fhirBaseUrl + "/Measure/" + madieMeasure.getCqlLibraryName())
        .setVersion(madieMeasure.getVersion().toString())
        .setEffectivePeriod(
            getPeriodFromDates(
                madieMeasure.getMeasurementPeriodStart(), madieMeasure.getMeasurementPeriodEnd()))
        .setPublisher(StringUtils.isBlank(steward) ? UNKNOWN : steward)
        .setCopyright(StringUtils.isBlank(copyright) ? UNKNOWN : copyright)
        .setDisclaimer(StringUtils.isBlank(disclaimer) ? UNKNOWN : disclaimer)
        .setRationale(rationale)
        .setLibrary(
            Collections.singletonList(
                new CanonicalType(fhirBaseUrl + "/Library/" + madieMeasure.getCqlLibraryName())))
        .setPurpose(UNKNOWN)
        .setContact(buildContactDetailUrl())
        .setGroup(buildGroups(madieMeasure.getGroups()))
        .setSupplementalData(buildSupplementalData(madieMeasure))
        .setMeta(buildMeasureMeta(madieMeasure));

    return measure;
  }

  public Meta buildMeasureMeta(Measure madieMeasure) {
    final Meta meta = new Meta();
    meta.setVersionId(madieMeasure.getVersionId());
    if (madieMeasure.getLastModifiedAt() != null) {
      meta.setLastUpdated(Date.from(madieMeasure.getLastModifiedAt()));
    }
    meta.addProfile(UriConstants.CqfMeasures.COMPUTABLE_MEASURE_PROFILE_URI);
    meta.addProfile(UriConstants.CqfMeasures.PUBLISHABLE_MEASURE_PROFILE_URI);
    meta.addProfile(UriConstants.CqfMeasures.EXECUTABLE_MEASURE_PROFILE_URI);
    return meta;
  }

  public List<MeasureGroupComponent> buildGroups(List<Group> madieGroups) {
    if (CollectionUtils.isNotEmpty(madieGroups)) {
      return madieGroups.stream().map(this::buildGroup).collect(Collectors.toList());
    } else {
      return null;
    }
  }

  private CodeableConcept getScoringUnitCode(Object scoringUnit) {
    if (scoringUnit == null) {
      return null;
    } else if (scoringUnit instanceof String) {
      String scoringUnitStr = (String) scoringUnit;
      return scoringUnitStr.trim().isEmpty()
          ? null
          : new CodeableConcept(new Coding(null, scoringUnitStr, scoringUnitStr));
    } else if (scoringUnit instanceof Map) {
      Map<String, Object> scoringUnitObj = (Map) scoringUnit;
      Map<String, Object> valueObj = (Map) scoringUnitObj.get("value");
      return new CodeableConcept(
          new Coding(
              (String) valueObj.get("system"),
              (String) valueObj.get("code"),
              (String) scoringUnitObj.get("label")));
    } else {
      return null;
    }
  }

  public MeasureGroupComponent buildGroup(Group madieGroup) {
    List<MeasureGroupPopulationComponent> measurePopulations = buildPopulations(madieGroup);
    measurePopulations.addAll(buildObservations(madieGroup));
    List<MeasureGroupStratifierComponent> measureStratifications = buildStratifications(madieGroup);
    // seems FHIR spec and fqm-execution want lowercase 'boolean', while other popBasis have
    // capitalized first letter
    final String popBasisValue =
        StringUtils.equalsIgnoreCase("boolean", madieGroup.getPopulationBasis())
            ? "boolean"
            : madieGroup.getPopulationBasis();
    final CodeableConcept scoringUnit = getScoringUnitCode(madieGroup.getScoringUnit());

    Element element =
        new MeasureGroupComponent()
            .setPopulation(measurePopulations)
            .setStratifier(measureStratifications)
            .setId(madieGroup.getId())
            .addExtension(
                new Extension(
                    UriConstants.CqfMeasures.SCORING_URI,
                    buildScoringConcept(madieGroup.getScoring())))
            .addExtension(
                new Extension(
                    UriConstants.CqfMeasures.POPULATION_BASIS, new CodeType(popBasisValue)));
    if (scoringUnit != null) {
      element.addExtension(new Extension(UriConstants.CqfMeasures.SCORING_UNIT_URI, scoringUnit));
    }
    return (MeasureGroupComponent) element;
  }

  private List<MeasureGroupPopulationComponent> buildPopulations(Group madieGroup) {
    return madieGroup.getPopulations().stream()
        .map(
            population -> {
              String populationCode = population.getName().toCode();
              String populationDisplay = population.getName().getDisplay();
              return (MeasureGroupPopulationComponent)
                  (new MeasureGroupPopulationComponent()
                          .setCode(
                              buildCodeableConcept(
                                  populationCode,
                                  UriConstants.POPULATION_SYSTEM_URI,
                                  populationDisplay))
                          .setCriteria(
                              buildExpression("text/cql.identifier", population.getDefinition()))
                          .setId(population.getId()))
                      .addExtension(buildPopulationTypeExtension(population, madieGroup));
              // TODO: Add an extension for measure observations
            })
        .collect(Collectors.toList());
  }

  private List<MeasureGroupPopulationComponent> buildObservations(Group madieGroup) {
    if (madieGroup.getMeasureObservations() == null
        || madieGroup.getMeasureObservations().isEmpty()) {
      return List.of();
    }
    return madieGroup.getMeasureObservations().stream()
        .map(
            measureObservation -> {
              MeasureGroupPopulationComponent observationPopulation =
                  (MeasureGroupPopulationComponent)
                      (new MeasureGroupPopulationComponent()
                          .setCode(
                              buildCodeableConcept(
                                  PopulationType.MEASURE_OBSERVATION.toCode(),
                                  UriConstants.POPULATION_SYSTEM_URI,
                                  PopulationType.MEASURE_OBSERVATION.getDisplay()))
                          .setCriteria(
                              buildExpression(
                                  "text/cql.identifier", measureObservation.getDefinition()))
                          .addExtension(
                              new Extension(
                                  UriConstants.CqfMeasures.AGGREGATE_METHOD_URI,
                                  new StringType(measureObservation.getAggregateMethod())))
                          .setId(measureObservation.getId()));
              if (measureObservation.getCriteriaReference() != null
                  && StringUtils.isNotBlank(measureObservation.getCriteriaReference())) {
                observationPopulation.addExtension(
                    new Extension(
                        UriConstants.CqfMeasures.CRITERIA_REFERENCE_URI,
                        new StringType(measureObservation.getCriteriaReference())));
              }
              return observationPopulation;
            })
        .toList();
  }

  private List<MeasureGroupStratifierComponent> buildStratifications(Group madieGroup) {
    AtomicReference<Extension> extension = new AtomicReference<Extension>();
    List<MeasureGroupStratifierComponent> measureStratifications = null;
    if (madieGroup.getStratifications() != null && !madieGroup.getStratifications().isEmpty()) {
      AtomicReference<Integer> i = new AtomicReference<>();
      i.set(Integer.valueOf(0));
      measureStratifications =
          madieGroup.getStratifications().stream()
              .map(
                  strat -> {
                    PopulationType associationPopulation = strat.getAssociation();
                    extension.set(
                        new Extension(
                            UriConstants.CqfMeasures.APPLIES_TO_URI,
                            buildCodeableConcept(
                                associationPopulation.toCode(),
                                UriConstants.POPULATION_SYSTEM_URI,
                                associationPopulation.getDisplay())));
                    i.set(Integer.valueOf(i.get().intValue() + 1));
                    return (MeasureGroupStratifierComponent)
                        (new MeasureGroupStratifierComponent()
                                .setCriteria(
                                    buildExpression(
                                        "text/cql.identifier", strat.getCqlDefinition())))
                            .setId(
                                StringUtils.isNotBlank(strat.getId())
                                    ? strat.getId()
                                    : i.get().toString())
                            .addExtension(extension.get());
                  })
              .collect(Collectors.toList());
    }
    return measureStratifications;
  }

  private Extension buildPopulationTypeExtension(Population population, Group madieGroup) {
    // TODO: I feel like this should be in a QICore specific module
    AtomicReference<Extension> extension = new AtomicReference<Extension>();
    AtomicReference<String> id = new AtomicReference<String>();
    if (population.getName().equals(PopulationType.DENOMINATOR)
        || population.getName().equals(PopulationType.NUMERATOR)) {
      madieGroup
          .getPopulations()
          .forEach(
              pop -> {
                // find the pop that has initial_populatino, associationType = population.getName()
                // and then set the extension
                if (pop.getName().equals(PopulationType.INITIAL_POPULATION)
                    && (pop.getAssociationType() != null
                        && pop.getAssociationType()
                            .toString()
                            .equalsIgnoreCase(population.getName().toCode()))) {
                  IBaseDatatype theValue = new StringType(pop.getId());
                  extension.set(
                      new Extension(UriConstants.CqfMeasures.CRITERIA_REFERENCE_URI, theValue));
                }
              });
    }

    // value is a codeable concept
    return extension.get();
  }

  private Expression buildExpression(String language, String expression) {
    return new Expression().setLanguage(language).setExpression(expression);
  }

  private Period getPeriodFromDates(Date startDate, Date endDate) {
    return new Period()
        .setStart(startDate, TemporalPrecisionEnum.DAY)
        .setEnd(endDate, TemporalPrecisionEnum.DAY);
  }

  public CodeableConcept buildScoringConcept(String scoring) {
    if (StringUtils.isEmpty(scoring)) {
      return null;
    }
    String code = scoring.toLowerCase();
    if ("continuous variable".equals(code)) {
      code = "continuous-variable";
    }
    return buildCodeableConcept(code, UriConstants.SCORING_SYSTEM_URI, scoring);
  }

  private CodeableConcept buildCodeableConcept(String code, String system, String display) {
    CodeableConcept codeableConcept = new CodeableConcept();
    codeableConcept.setCoding(new ArrayList<>());
    codeableConcept.getCoding().add(buildCoding(code, system, display));
    return codeableConcept;
  }

  private Coding buildCoding(String code, String system, String display) {
    return new Coding().setCode(code).setSystem(system).setDisplay(display);
  }

  private List<ContactDetail> buildContactDetailUrl() {
    ContactDetail contactDetail = new ContactDetail();
    contactDetail.setTelecom(new ArrayList<>());
    contactDetail.getTelecom().add(buildContactPoint());

    List<ContactDetail> contactDetails = new ArrayList<>(1);
    contactDetails.add(contactDetail);

    return contactDetails;
  }

  private ContactPoint buildContactPoint() {
    return new ContactPoint()
        .setValue("https://cms.gov")
        .setSystem(ContactPoint.ContactPointSystem.URL);
  }

  private List<MeasureSupplementalDataComponent> buildSupplementalData(Measure madieMeasure) {
    List<MeasureSupplementalDataComponent> measureSupplementalDataComponents = new ArrayList<>();
    measureSupplementalDataComponents.addAll(buildSupplementalDataElements(madieMeasure));
    measureSupplementalDataComponents.addAll(buildRiskAdjustmentFactors(madieMeasure));
    return measureSupplementalDataComponents;
  }

  private List<MeasureSupplementalDataComponent> buildSupplementalDataElements(
      Measure madieMeasure) {
    if (madieMeasure.getSupplementalData() == null) {
      return Collections.emptyList();
    }
    return madieMeasure.getSupplementalData().stream()
        .map(
            supplementalData -> {
              var measureSupplementalDataComponent = new MeasureSupplementalDataComponent();
              measureSupplementalDataComponent.setId(
                  supplementalData.getDefinition().toLowerCase().replace(" ", "-"));
              measureSupplementalDataComponent.setCriteria(
                  buildExpression("text/cql-identifier", supplementalData.getDefinition()));
              measureSupplementalDataComponent.setDescription(supplementalData.getDescription());
              measureSupplementalDataComponent.setUsage(
                  List.of(
                      buildCodeableConcept(
                          "supplemental-data",
                          "http://terminology.hl7.org/CodeSystem/measure-data-usage",
                          null)));
              return measureSupplementalDataComponent;
            })
        .collect(Collectors.toList());
  }

  private List<MeasureSupplementalDataComponent> buildRiskAdjustmentFactors(Measure madieMeasure) {
    if (madieMeasure.getRiskAdjustments() == null) {
      return Collections.emptyList();
    }
    return madieMeasure.getRiskAdjustments().stream()
        .map(
            riskAdjustment -> {
              var measureSupplementalDataComponent = new MeasureSupplementalDataComponent();
              measureSupplementalDataComponent.setId(
                  riskAdjustment.getDefinition().toLowerCase().replace(" ", "-"));
              measureSupplementalDataComponent.setCriteria(
                  buildExpression("text/cql-identifier", riskAdjustment.getDefinition()));
              measureSupplementalDataComponent.setUsage(
                  List.of(
                      buildCodeableConcept(
                          "risk-adjustment-factor",
                          "http://terminology.hl7.org/CodeSystem/measure-data-usage",
                          null)));
              measureSupplementalDataComponent.setDescription(riskAdjustment.getDescription());
              return measureSupplementalDataComponent;
            })
        .collect(Collectors.toList());
  }
}
