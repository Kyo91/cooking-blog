alter table photos
  add constraint photos_filename_length_valid
    check (char_length(original_filename) <= 255),
  add constraint photos_comment_length_valid
    check (comment is null or char_length(comment) <= 2000);
