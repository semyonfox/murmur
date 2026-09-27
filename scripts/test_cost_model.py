import json
from decimal import Decimal
import unittest

from cost_model import ROOT, duration_profile, estimate


class CostModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rates = json.loads((ROOT / "config" / "pricing.snapshot.json").read_text())

    def groq(self, durations):
        return estimate(durations, self.rates["stt"]["groq-turbo"], self.rates["cleanup"]["groq-oss20"])

    def test_short_requests_have_a_minimum_not_ten_second_blocks(self):
        result = self.groq([Decimal(5), Decimal(11), Decimal(29)])
        self.assertEqual(result["stt_billable_seconds_estimate"], Decimal(50))

    def test_partial_final_session_is_billed_separately(self):
        durations = duration_profile(Decimal("1.05"), Decimal(30))
        self.assertEqual(durations, [Decimal(30), Decimal(30), Decimal(3)])
        self.assertEqual(self.groq(durations)["stt_billable_seconds_estimate"], Decimal(70))

    def test_five_hour_scenario_includes_prompt_and_reasoning(self):
        result = self.groq(duration_profile(Decimal(300), Decimal(30)))
        self.assertEqual(result["sessions"], 600)
        self.assertEqual(result["cleanup_input_tokens_estimate"], 540000)
        self.assertEqual(result["cleanup_output_tokens_estimate"], 180000)
        self.assertEqual(result["inference_usd"], Decimal("0.2945"))

    def test_more_short_clips_cost_more_for_equal_audio(self):
        long = self.groq(duration_profile(Decimal(300), Decimal(30)))
        short = self.groq(duration_profile(Decimal(300), Decimal(5)))
        self.assertEqual(short["inference_usd"], Decimal("0.8545"))
        self.assertGreater(short["inference_usd"], long["inference_usd"])

    def test_invalid_durations_are_rejected(self):
        for value in ["0", "-1", "NaN", "Infinity"]:
            with self.assertRaises(ValueError):
                duration_profile(Decimal(value), Decimal(30))


if __name__ == "__main__":
    unittest.main()
