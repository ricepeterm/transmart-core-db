/*
 * Copyright © 2013-2014 The Hyve B.V.
 *
 * This file is part of transmart-core-db.
 *
 * Transmart-core-db is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * transmart-core-db.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.transmartproject.db.dataquery.highdim.rnaseqcog

import com.google.common.collect.Lists
import grails.test.mixin.TestMixin
import groovy.test.GroovyAssert
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.transmartproject.core.dataquery.TabularResult
import org.transmartproject.core.dataquery.assay.Assay
import org.transmartproject.core.dataquery.highdim.AssayColumn
import org.transmartproject.core.dataquery.highdim.HighDimensionDataTypeResource
import org.transmartproject.core.dataquery.highdim.HighDimensionResource
import org.transmartproject.core.dataquery.highdim.assayconstraints.AssayConstraint
import org.transmartproject.core.dataquery.highdim.dataconstraints.DataConstraint
import org.transmartproject.core.dataquery.highdim.projections.Projection
import org.transmartproject.core.exceptions.InvalidArgumentsException
import org.transmartproject.core.querytool.ConstraintByOmicsValue
import org.transmartproject.db.dataquery.highdim.HighDimTestData
import org.transmartproject.db.dataquery.highdim.mirna.MirnaTestData
import org.transmartproject.db.test.RuleBasedIntegrationTestMixin

import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.*
import static org.transmartproject.db.dataquery.highdim.HighDimTestData.createTestAssays
import static org.transmartproject.db.test.Matchers.hasSameInterfaceProperties

@TestMixin(RuleBasedIntegrationTestMixin)
class RnaSeqCogEndToEndRetrievalTests {

    private static final double DELTA = 0.0001
    TabularResult<AssayColumn, RnaSeqCogDataRow> result

    RnaSeqCogTestData testData = new RnaSeqCogTestData()

    HighDimensionDataTypeResource<RnaSeqCogDataRow> rnaSeqCogResource

    HighDimensionResource highDimensionResourceService

    Projection projection

    AssayConstraint trialNameConstraint

    private final String conceptCode = 'concept code #1'
    
    String conceptKey

    @Before
    void setUp() {
        testData.saveAll()

        rnaSeqCogResource = highDimensionResourceService.
                getSubResourceForType('rnaseq_cog')

        projection = rnaSeqCogResource.createProjection(
                [:], Projection.ZSCORE_PROJECTION)

        trialNameConstraint = rnaSeqCogResource.createAssayConstraint(
                AssayConstraint.TRIAL_NAME_CONSTRAINT,
                name: RnaSeqCogTestData.TRIAL_NAME)

        conceptKey = '\\\\' + testData.concept.tableAccesses[0].tableCode + testData.concept.conceptDimensions[0].conceptPath
    }

    @After
    void after() {
        result?.close()
    }

    @Test
    void basicTest() {
        result = rnaSeqCogResource.retrieveData([ trialNameConstraint ],
                [], projection)

        assertThat result, allOf(
                hasProperty('columnsDimensionLabel', is('Sample codes')),
                hasProperty('rowsDimensionLabel',    is('Transcripts')),
                hasProperty('indicesList', contains(
                        testData.assays.reverse().collect { Assay it ->
                            hasSameInterfaceProperties(Assay, it)
                        }.collect { is it })))

        def rows = Lists.newArrayList result

        assertThat(rows, contains(
                contains(testData.data[-5..-6]*.zscore.collect { Double it -> closeTo it, DELTA }),
                contains(testData.data[-3..-4]*.zscore.collect { Double it -> closeTo it, DELTA }),
                contains(testData.data[-1..-2]*.zscore.collect { Double it -> closeTo it, DELTA })))
    }

    @Test
    void testDataRowsProperties() {
        result = rnaSeqCogResource.retrieveData([ trialNameConstraint ],
                [], projection)

        assertThat Lists.newArrayList(result), contains(
                testData.annotations.collect { DeRnaseqAnnotation annotation ->
                    allOf(
                            hasProperty('label',     is(annotation.id)),
                            hasProperty('bioMarker', is(annotation.geneSymbol)))
                }
        )
    }

    @Test
    void testLogIntensityProjection() {
        def logIntensityProjection = rnaSeqCogResource.createProjection(
                [:], Projection.LOG_INTENSITY_PROJECTION)

        result = rnaSeqCogResource.retrieveData(
                [ trialNameConstraint ], [], logIntensityProjection)

        def resultList = Lists.newArrayList(result)

        assertThat resultList, containsInAnyOrder(
                testData.annotations.collect {
                    getDataMatcherForAnnotation(it, 'logIntensity')
                })
    }

    @Test
    void testDefaultRealProjection() {
        result = rnaSeqCogResource.retrieveData([ trialNameConstraint ], [],
                rnaSeqCogResource.createProjection([:], Projection.DEFAULT_REAL_PROJECTION))

        assertThat Lists.newArrayList(result), hasItem(allOf(
                hasProperty('label', is(testData.data[-1].annotation.id)) /* VNN3 */,
                contains(testData.data[-1..-2]*.rawIntensity.collect { Double it -> closeTo it, DELTA })
        ))
    }

    @Test
    void testGeneConstraint() {
        DataConstraint geneConstraint = rnaSeqCogResource.createDataConstraint(
                DataConstraint.GENES_CONSTRAINT,
                names: [ 'BOGUSVNN3' ])

        result = rnaSeqCogResource.retrieveData([ trialNameConstraint ],
                [ geneConstraint ],
                rnaSeqCogResource.createProjection([:], Projection.DEFAULT_REAL_PROJECTION))

        assertThat Lists.newArrayList(result), contains(
                hasProperty('bioMarker', is('BOGUSVNN3')))
    }

    @Test
    void testMissingAssaysAllowedSucceeds() {
        testWithMissingDataAssay(-50000L)
        assertThat Lists.newArrayList(result.rows), everyItem(
                hasProperty('data', allOf(
                        hasSize(2), // for the three assays
                        contains(
                                is(notNullValue()),
                                is(notNullValue()),
                        )
                ))
        )
    }

    private TabularResult testWithMissingDataAssay(Long baseAssayId) {
        def extraAssays = createTestAssays([ testData.patients[0] ], baseAssayId,
                testData.platform, MirnaTestData.TRIAL_NAME)
        HighDimTestData.save extraAssays

        List assayConstraints = [trialNameConstraint]

        result =
            rnaSeqCogResource.retrieveData assayConstraints, [], projection
    }

    def getDataMatcherForAnnotation(DeRnaseqAnnotation annotation,
                                    String property) {
        contains testData.data.
                findAll { it.annotation == annotation }.
                sort    { it.assay.id }. // data is sorted by assay id
                collect { closeTo it."$property" as Double, DELTA }
    }

    @Test
    void testAnnotationSearchMulti() {
        // test multiple result, alphabetical order
        def symbols = rnaSeqCogResource.searchAnnotation(conceptCode, 'BOGUS', 'geneSymbol')

        assertThat symbols, allOf(
                hasSize(3),
                // should be in alphabetical order
                contains(
                        equalTo("BOGUSCPO"),
                        equalTo("BOGUSRQCD1"),
                        equalTo("BOGUSVNN3")
                )
        )
    }

    @Test
    void testAnnotationSearchSingle() {
        // test single result
        def symbols = rnaSeqCogResource.searchAnnotation(conceptCode, 'BOGUSC', 'geneSymbol')
        assertThat symbols, allOf(
                hasSize(1),
                contains(equalTo('BOGUSCPO'))
        )
    }

    @Test
    void testAnnotationSearchNoResult() {
        // test non-occuring name
        def symbols = rnaSeqCogResource.searchAnnotation(conceptCode, 'FOO', 'geneSymbol')
        assertThat symbols, hasSize(0)
    }

    @Test
    void testAnnotationSearchInvalidProperty() {
        // test invalid search property
        GroovyAssert.shouldFail(InvalidArgumentsException.class) {rnaSeqCogResource.searchAnnotation(conceptCode, 'BOGUS', 'FOO')}
    }

    @Test
    void testAnnotationConstraint() {
        def constraint = new ConstraintByOmicsValue(
                omicsType: ConstraintByOmicsValue.OmicsType.GENE_EXPRESSION,
                property: 'geneSymbol',
                selector: 'BOGUSCPO',
                projectionType: Projection.LOG_INTENSITY_PROJECTION
        )

        def distribution = rnaSeqCogResource.getDistribution(constraint, conceptKey, null)
        def correctValues = testData.data.findAll {it.annotation.geneSymbol == 'BOGUSCPO'}.collectEntries {[it.patient.id, it.logIntensity]}
        assertThat distribution.size(), greaterThanOrEqualTo(1)
        assert distribution.equals(correctValues) // groovy maps are equal if they have same size, keys and values
    }

    @Test
    void testLogIntensityConstraint() {
        def constraint = new ConstraintByOmicsValue(
                omicsType: ConstraintByOmicsValue.OmicsType.GENE_EXPRESSION,
                property: 'geneSymbol',
                selector: 'BOGUSCPO',
                projectionType: Projection.LOG_INTENSITY_PROJECTION,
                operator: 'BETWEEN',
                constraint: '-3:-2'
        )

        def distribution = rnaSeqCogResource.getDistribution(constraint, conceptKey, null)
        def correctValues = testData.data.findAll {it.annotation.geneSymbol == 'BOGUSCPO' && -3 <= it.logIntensity && it.logIntensity <= -2}.collectEntries {[it.patient.id, it.logIntensity]}
        assertThat distribution.size(), greaterThanOrEqualTo(1)
        assert distribution.equals(correctValues) // groovy maps are equal if they have same size, keys and values
    }

    @Test
    void testRawIntensityConstraint() {
        def constraint = new ConstraintByOmicsValue(
                omicsType: ConstraintByOmicsValue.OmicsType.GENE_EXPRESSION,
                property: 'geneSymbol',
                selector: 'BOGUSCPO',
                projectionType: Projection.DEFAULT_REAL_PROJECTION,
                operator: 'BETWEEN',
                constraint: '0.05:0.15'
        )

        def distribution = rnaSeqCogResource.getDistribution(constraint, conceptKey, null)
        def correctValues = testData.data.findAll {it.annotation.geneSymbol == 'BOGUSCPO' && 0.05 <= it.rawIntensity && it.rawIntensity <= 0.15}.collectEntries {[it.patient.id, it.rawIntensity]}
        assertThat distribution.size(), greaterThanOrEqualTo(1)
        assert distribution.equals(correctValues) // groovy maps are equal if they have same size, keys and values
    }

    @Test
    void testZScoreConstraint() {
        def constraint = new ConstraintByOmicsValue(
                omicsType: ConstraintByOmicsValue.OmicsType.GENE_EXPRESSION,
                property: 'geneSymbol',
                selector: 'BOGUSCPO',
                projectionType: Projection.ZSCORE_PROJECTION,
                operator: 'BETWEEN',
                constraint: '-1.5:0'
        )

        def distribution = rnaSeqCogResource.getDistribution(constraint, conceptKey, null)
        def correctValues = testData.data.findAll {it.annotation.geneSymbol == 'BOGUSCPO' && -1.5 <= it.zscore && it.zscore <= 0}.collectEntries {[it.patient.id, it.zscore]}
        assertThat distribution.size(), greaterThanOrEqualTo(1)
        assert distribution.equals(correctValues) // groovy maps are equal if they have same size, keys and values
    }

}
