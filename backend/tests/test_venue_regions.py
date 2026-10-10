from app.geo import regions_verdict


def test_location_can_be_inside_second_region():
    verdict, distance = regions_verdict(12.98, 77.60, 5, [(12.97, 77.59, 25), (12.98, 77.60, 40)])
    assert verdict == "inside" and distance == 0


def test_uncertainty_and_outside_apply_across_all_regions():
    regions = [(12.97, 77.59, 10), (12.98, 77.60, 20)]
    assert regions_verdict(12.98, 77.60, 30, regions)[0] == "uncertain"
    assert regions_verdict(13.0, 77.7, 5, regions)[0] == "outside"


def test_regions_saved_and_returned_after_reloading(api):
    regions = [dict(venue_lat=12.97, venue_lng=77.59, radius_m=25), dict(venue_lat=12.98, venue_lng=77.60, radius_m=60)]
    response = api.post("faculty1", "/activities", json=dict(
        name="Two blocks", venue_name="Campus", **regions[0], venue_regions=regions,
        starts_at="2026-10-10T10:00:00Z", ends_at="2026-10-10T12:00:00Z", student_ids=["u_s1"],
    ))
    assert response.status_code == 201, response.text
    activity = response.json()
    assert activity["venue_regions"] == regions
    assert api.get("faculty1", f"/activities/{activity['id']}").json()["venue_regions"] == regions


def test_legacy_activity_returns_a_single_region(api):
    activity = api.make_activity()
    assert activity["venue_regions"] == [dict(venue_lat=activity["venue_lat"], venue_lng=activity["venue_lng"], radius_m=activity["radius_m"])]


def test_zero_radius_can_be_saved(api):
    region = dict(venue_lat=12.97, venue_lng=77.59, radius_m=0)
    activity = api.make_activity(radius_m=0, venue_regions=[region])
    assert activity["radius_m"] == 0
    assert activity["venue_regions"] == [region]


def test_checkin_inside_second_circle_is_recorded(api):
    regions = [dict(venue_lat=12.97, venue_lng=77.59, radius_m=25), dict(venue_lat=12.98, venue_lng=77.60, radius_m=60)]
    activity = api.make_activity(starts_in_min=0, venue_regions=regions)
    api.enroll("student1", "A")
    result = api.do("student1", activity["id"], "check_in", "A", lat=12.98, lng=77.60, accuracy=5)
    assert result.status_code == 200, result.text
    assert result.json()["location"]["verdict"] == "inside"
    assert result.json()["status"] == "recorded"
