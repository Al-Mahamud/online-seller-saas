-- Chattogram city's areas: the Chattogram Metropolitan Police thanas, as areas of Chattogram district
-- (id 8) of kind METRO_THANA, as V18 did for Dhaka. The dataset lists only the district's upazilas, so
-- "Chattogram city" delivery charges could not be set. Karnaphuli thana is left out: the district
-- already has it, as the upazila Karnafuli (কর্ণফুলী).

INSERT INTO areas (id, district_id, kind, name_en, name_bn) VALUES
    (9101, 8, 'METRO_THANA', 'Kotwali', 'কোতোয়ালী'),
    (9102, 8, 'METRO_THANA', 'Chawkbazar', 'চকবাজার'),
    (9103, 8, 'METRO_THANA', 'Bakalia', 'বাকলিয়া'),
    (9104, 8, 'METRO_THANA', 'Panchlaish', 'পাঁচলাইশ'),
    (9105, 8, 'METRO_THANA', 'Chandgaon', 'চান্দগাঁও'),
    (9106, 8, 'METRO_THANA', 'Bayazid Bostami', 'বায়েজিদ বোস্তামী'),
    (9107, 8, 'METRO_THANA', 'Khulshi', 'খুলশী'),
    (9108, 8, 'METRO_THANA', 'Pahartali', 'পাহাড়তলী'),
    (9109, 8, 'METRO_THANA', 'Akbar Shah', 'আকবর শাহ'),
    (9110, 8, 'METRO_THANA', 'Double Mooring', 'ডবলমুরিং'),
    (9111, 8, 'METRO_THANA', 'Halishahar', 'হালিশহর'),
    (9112, 8, 'METRO_THANA', 'Bandar', 'বন্দর'),
    (9113, 8, 'METRO_THANA', 'EPZ', 'ইপিজেড'),
    (9114, 8, 'METRO_THANA', 'Patenga', 'পতেঙ্গা'),
    (9115, 8, 'METRO_THANA', 'Sadarghat', 'সদরঘাট');
