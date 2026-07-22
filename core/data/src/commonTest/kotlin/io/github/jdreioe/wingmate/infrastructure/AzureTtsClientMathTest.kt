package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Voice
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AzureTtsClientMathTest {
    @Test
    fun mathModeGeneratesMathMlWithSelectedVoiceAndLanguage() {
        val ssml = AzureTtsClient.generateSsml(
            text = "a²+b²=c²",
            voice = Voice(
                name = "en-US-AvaMultilingualNeural",
                selectedLanguage = "en-US",
                mathMode = true
            )
        )

        assertContains(ssml, "xmlns:mstts=\"http://www.w3.org/2001/mstts\"")
        assertContains(ssml, "xml:lang=\"en-US\"")
        assertContains(ssml, "<voice name=\"en-US-AvaMultilingualNeural\">")
        assertContains(ssml, "<lang xml:lang=\"en-US\">")
        assertContains(ssml, "<math xmlns=\"http://www.w3.org/1998/Math/MathML\">")
        assertContains(ssml, "<msup><mi>a</mi><mn>2</mn></msup>")
        assertContains(ssml, "<mo>+</mo>")
        assertContains(ssml, "<msup><mi>b</mi><mn>2</mn></msup>")
        assertContains(ssml, "<mo>=</mo>")
        assertContains(ssml, "<msup><mi>c</mi><mn>2</mn></msup>")
        assertEquals(false, ssml.contains("domain=\"Math\""))
    }

    @Test
    fun mathModeStructuresRootsPowersAndEscapesOperators() {
        val ssml = AzureTtsClient.generateSsml(
            text = "√(x^2)<10&y",
            voice = Voice(name = "en-US-JennyNeural", mathMode = true)
        )

        assertContains(ssml, "<msqrt><msup><mi>x</mi><mn>2</mn></msup></msqrt>")
        assertContains(ssml, "<mo>&lt;</mo><mn>10</mn><mo>&amp;</mo><mi>y</mi>")
    }

    @Test
    fun slashCreatesMathMlFraction() {
        val ssml = AzureTtsClient.generateSsml(
            text = "a²/b²+1/2",
            voice = Voice(name = "en-US-JennyNeural", mathMode = true)
        )

        assertContains(
            ssml,
            "<mfrac><msup><mi>a</mi><mn>2</mn></msup><msup><mi>b</mi><mn>2</mn></msup></mfrac>"
        )
        assertContains(ssml, "<mo>+</mo><mfrac><mn>1</mn><mn>2</mn></mfrac>")
        assertEquals(false, ssml.contains("<mo>/</mo>"))
    }

    @Test
    fun functionNotationCreatesFunctionApplicationAndMultiplication() {
        val ssml = AzureTtsClient.generateSsml(
            text = "f(x) = a*x + b",
            voice = Voice(name = "en-US-JennyNeural", mathMode = true)
        )

        assertContains(
            ssml,
            "<mrow><mi>f</mi><mo>⁡</mo><mfenced><mi>x</mi></mfenced></mrow>"
        )
        assertContains(
            ssml,
            "<mo>=</mo><mi>a</mi><mo>×</mo><mi>x</mi><mo>+</mo><mi>b</mi>"
        )
        assertEquals(false, ssml.contains("<mo>*</mo>"))
    }

    @Test
    fun adjacentOperandsUseInvisibleTimes() {
        val ssml = AzureTtsClient.generateSsml(
            text = "x2^3+2x^3",
            voice = Voice(name = "en-US-JennyNeural", mathMode = true)
        )

        assertContains(
            ssml,
            "<mi>x</mi><mo>⁢</mo><msup><mn>2</mn><mn>3</mn></msup>"
        )
        assertContains(
            ssml,
            "<mn>2</mn><mo>⁢</mo><msup><mi>x</mi><mn>3</mn></msup>"
        )
    }

    @Test
    fun preservesCompleteMathMlDocumentsWithoutFlatteningTheirStructure() {
        val mathMl = """
            <math xmlns="http://www.w3.org/1998/Math/MathML" display="block">
                <semantics>
                    <mtable columnalign="center">
                        <mtr><mtd><mmultiscripts><mi>T</mi><mi>i</mi><mi>j</mi><mprescripts/><none/><mi>k</mi></mmultiscripts></mtd></mtr>
                        <mtr><mtd><munderover><mo>∑</mo><mrow><mi>i</mi><mo>=</mo><mn>0</mn></mrow><mi>n</mi></munderover></mtd></mtr>
                    </mtable>
                    <annotation-xml encoding="MathML-Content">
                        <apply><plus/><ci>x</ci><cn>1</cn></apply>
                    </annotation-xml>
                </semantics>
            </math>
        """.trimIndent()

        val ssml = AzureTtsClient.generateSsml(
            text = mathMl,
            voice = Voice(name = "en-US-JennyNeural", mathMode = true)
        )

        assertContains(ssml, mathMl)
        assertEquals(1, Regex("<math(?:\\s|>)").findAll(ssml).count())
    }

    @Test
    fun acceptsAnyWellFormedMathMlFragmentInsideGeneratedMathRoot() {
        val fragment = "<mroot><mrow><mi>x</mi><mo>+</mo><mn>1</mn></mrow><mn>3</mn></mroot>"

        val ssml = AzureTtsClient.generateSsml(
            text = fragment,
            voice = Voice(name = "en-US-JennyNeural", mathMode = true)
        )

        assertContains(
            ssml,
            "<math xmlns=\"http://www.w3.org/1998/Math/MathML\">$fragment</math>"
        )
    }

    @Test
    fun malformedMathMlCannotEscapeIntoSurroundingSsml() {
        val ssml = AzureTtsClient.generateSsml(
            text = "<math><mi>x</mi></math></voice><voice name=\"injected\">oops",
            voice = Voice(name = "en-US-JennyNeural", mathMode = true)
        )

        assertEquals(false, ssml.contains("<voice name=\"injected\">"))
        assertEquals(1, Regex("<voice name=").findAll(ssml).count())
    }

    @Test
    fun regularModeIsUnchanged() {
        val ssml = AzureTtsClient.generateSsml(
            text = "x + 2",
            voice = Voice(name = "en-US-JennyNeural", mathMode = false)
        )

        assertEquals(false, ssml.contains("domain=\"Math\""))
        assertEquals(false, ssml.contains("<math"))
    }
}
