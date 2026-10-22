-- A salesman can work for companies run by different sales managers (for example LV and ZT under one
-- manager, ZF and GL under another). The manager is now kept per company; users.manager_id stays as
-- the salesman's main manager.
alter table user_companies add column manager_id int references users(id);
update user_companies uc set manager_id = u.manager_id from users u where u.id = uc.user_id and u.role = 'salesman';
