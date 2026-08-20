class DummyRankingModel:
    """A dummy ranking model"""

    def score(self, scenario:dict)-> float:
        #Returns a dummy score to insure that the harness is working correctly
        return 1.0